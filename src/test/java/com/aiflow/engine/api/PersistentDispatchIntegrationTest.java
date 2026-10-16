package com.aiflow.engine.api;

import com.aiflow.engine.api.service.PersistentDispatchWorker;
import com.aiflow.engine.api.service.RunCoordinator;
import com.aiflow.engine.api.service.SessionMessageService;
import com.aiflow.engine.api.service.SessionService;
import com.aiflow.engine.api.service.UserService;
import com.aiflow.engine.api.service.WorkflowService;
import com.aiflow.engine.approval.ApprovalDecision;
import com.aiflow.engine.approval.ApprovalPolicy;
import com.aiflow.engine.approval.ApprovalResumeService;
import com.aiflow.engine.approval.ApprovalService;
import com.aiflow.engine.approval.ApprovalStatus;
import com.aiflow.engine.approval.RejectAction;
import com.aiflow.engine.approval.TimeoutAction;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.config.DispatchProperties;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.graph.EdgeDef;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.persistence.dispatch.PgDispatchTaskStore;
import com.aiflow.engine.persistence.entity.ApprovalEntity;
import com.aiflow.engine.persistence.repo.ApprovalEventRepository;
import com.aiflow.engine.persistence.repo.ApprovalRepository;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.NodeRunRepository;
import com.aiflow.engine.persistence.repo.RunCheckpointRepository;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.run.RunStatus;
import com.aiflow.engine.session.DispatchKind;
import com.aiflow.engine.session.DispatchStatus;
import com.aiflow.engine.session.DispatchTaskStore;
import com.aiflow.engine.session.SessionLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the application transaction boundaries with durable dispatch enabled. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dispatch-root-wiring;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "aiflow.dispatch.enabled=true",
        "aiflow.dispatch.worker-enabled=false",
        "aiflow.engine.session-queue-capacity=1",
        "aiflow.approval.sweep-enabled=false",
        "aiflow.events.mode=memory",
        "aiflow.memory.summary-enabled=false",
        "aiflow.long-memory.extract-enabled=false",
        "aiflow.auth.enabled=false"
})
@AutoConfigureMockMvc
@Import(PersistentDispatchIntegrationTest.ProbeConfiguration.class)
class PersistentDispatchIntegrationTest {
    @Autowired private SessionMessageService messages;
    @Autowired private SessionService sessions;
    @Autowired private UserService users;
    @Autowired private WorkflowService workflows;
    @Autowired private WorkflowRunRepository runs;
    @Autowired private MessageRepository messageRows;
    @Autowired private ApprovalService approvals;
    @Autowired private ApprovalRepository approvalRows;
    @Autowired private ApprovalEventRepository approvalEvents;
    @Autowired private RunCheckpointRepository checkpoints;
    @Autowired private NodeRunRepository nodes;
    @Autowired private ApprovalResumeService approvalResults;
    @Autowired private RunCoordinator coordinator;
    @Autowired private DispatchTaskStore tasks;
    @Autowired private RunEventBus events;
    @Autowired private MeterRegistry meters;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CountingNode countingNode;
    @Autowired private LockProbe lockProbe;
    @MockitoSpyBean private PgDispatchTaskStore taskStoreSpy;

    private final List<ManualWorker> manualWorkers = new ArrayList<>();

    @AfterEach
    void cleanIsolatedDatabase() throws Exception {
        for (ManualWorker worker : manualWorkers) {
            worker.close();
        }
        manualWorkers.clear();
        reset(taskStoreSpy);
        // Only this in-memory context uses these tables; no scheduled workers scan it.
        jdbc.update("delete from session_dispatch_tasks");
        jdbc.update("delete from approval_events");
        jdbc.update("delete from approvals");
        jdbc.update("delete from run_checkpoints");
        jdbc.update("delete from node_runs");
        jdbc.update("delete from messages");
        jdbc.update("delete from workflow_runs");
        jdbc.update("delete from sessions");
        countingNode.clear();
        lockProbe.clear();
    }

    @Test
    void acceptedMessageCommitsUserRunAndProcessTaskTogether() {
        SessionRef session = session(false);
        var accepted = submit(session, "accepted", "事务内的一条消息");
        var message = messageRows.findById(accepted.messageId()).orElseThrow();
        assertThat(message.getRole()).isEqualTo("USER");
        assertThat(message.getRunId()).isEqualTo(accepted.runId());
        assertThat(runs.findById(accepted.runId()).orElseThrow().getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(tasks.findByRun(accepted.runId())).singleElement().satisfies(task -> {
            assertThat(task.kind()).isEqualTo(DispatchKind.PROCESS);
            assertThat(task.status()).isEqualTo(DispatchStatus.QUEUED);
            assertThat(task.sessionId()).isEqualTo(session.id());
        });
    }

    @Test
    void duplicateIdReturnsOriginalRunEvenWhenTheQueueIsFull() {
        SessionRef session = session(false);
        var first = submit(session, "same-client-id", "原始正文");
        var duplicate = submit(session, "same-client-id", "重复请求不能覆盖正文");
        assertThat(duplicate).isEqualTo(first);
        assertThat(messageRows.countBySessionId(session.id())).isEqualTo(1);
        assertThat(messageRows.findById(first.messageId()).orElseThrow().getContent()).isEqualTo("原始正文");
        assertThat(tasks.findByRun(first.runId())).hasSize(1);
        assertThat(runCount(session.id())).isEqualTo(1);
    }

    @Test
    void capacity429LeavesNoUserRunTaskOrReservedIdempotencyKey() throws Exception {
        SessionRef session = session(false);
        var first = submit(session, "fills-capacity", "占据队列");
        long taskCount = taskCount(session.id());
        mvc.perform(post("/api/v1/sessions/" + session.id() + "/messages")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("clientMsgId", "retry-after-429", "content", "稍后重试"))))
                .andExpect(status().isTooManyRequests());
        assertThat(messageRows.countBySessionId(session.id())).isEqualTo(1);
        assertThat(runCount(session.id())).isEqualTo(1);
        assertThat(taskCount(session.id())).isEqualTo(taskCount);
        assertThat(messageRows.findFirstBySessionIdAndClientMsgId(session.id(), "retry-after-429")).isEmpty();

        tasks.cancelQueued(first.runId(), coordinator::cancelQueued);
        var retried = submit(session, "retry-after-429", "稍后重试");
        assertThat(retried.runId()).isNotEqualTo(first.runId());
        assertThat(tasks.findByRun(retried.runId())).singleElement()
                .extracting(task -> task.status()).isEqualTo(DispatchStatus.QUEUED);
    }

    @Test
    void processInsertFailureRollsBackAlreadyFlushedUserAndRun() {
        SessionRef session = session(false);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("injected failure after PROCESS insert");
        }).when(taskStoreSpy).enqueueProcess(any(), any());
        assertThatThrownBy(() -> submit(session, "rollback-client-id", "已flush的消息也要回滚"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(messageRows.countBySessionId(session.id())).isZero();
        assertThat(runCount(session.id())).isZero();
        assertThat(taskCount(session.id())).isZero();
        reset(taskStoreSpy);
        assertThat(submit(session, "rollback-client-id", "回滚后可重试").runId()).isNotNull();
    }

    @Test
    void approvalDecisionCommitsOneResumeAndDuplicateDecisionCannotCreateAnother() throws Exception {
        SessionRef session = session(true);
        var accepted = suspend(session);
        ApprovalEntity approval = approval(accepted.runId());
        UUID checkpointId = checkpoints.findFirstByRunIdAndConsumedAtIsNullOrderBySeqDesc(accepted.runId())
                .orElseThrow().getId();
        approvals.decide(approval.getId(), ApprovalDecision.APPROVE, session.actor(), "批准");
        assertThat(approvalRows.findById(approval.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(tasks.findByRun(accepted.runId())).hasSize(2).last().satisfies(task -> {
            assertThat(task.kind()).isEqualTo(DispatchKind.RESUME);
            assertThat(task.checkpointId()).isEqualTo(checkpointId);
            assertThat(task.approvalId()).isEqualTo(approval.getId());
            assertThat(task.status()).isEqualTo(DispatchStatus.QUEUED);
        });
        assertThatThrownBy(() -> approvals.decide(approval.getId(), ApprovalDecision.APPROVE, session.actor(), "重复"))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(tasks.findByRun(accepted.runId())).hasSize(2);
    }

    @Test
    void resumeInsertFailureRollsBackDecisionAndApprovalEventTogether() throws Exception {
        SessionRef session = session(true);
        var accepted = suspend(session);
        ApprovalEntity approval = approval(accepted.runId());
        int initialEvents = approvalEvents.findByApprovalIdOrderByCreatedAtAsc(approval.getId()).size();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("injected failure after RESUME insert");
        }).when(taskStoreSpy).enqueueResume(any(), any(), any(), any());
        assertThatThrownBy(() -> approvals.decide(approval.getId(), ApprovalDecision.APPROVE, session.actor(), "不能单独提交"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(approvalRows.findById(approval.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approvalEvents.findByApprovalIdOrderByCreatedAtAsc(approval.getId())).hasSize(initialEvents);
        assertThat(tasks.findByRun(accepted.runId())).hasSize(1);
        assertThat(runs.findById(accepted.runId()).orElseThrow().getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(checkpoints.findFirstByRunIdAndConsumedAtIsNullOrderBySeqDesc(accepted.runId())).isPresent();
    }

    @Test
    void incompleteSuspensionCannotCommitAnApprovalWithoutAResumeCheckpoint() {
        SessionRef session = session(true);
        var accepted = submit(session, "partial-suspension", "模拟检查点尚未落完");
        tasks.cancelQueued(accepted.runId(), ignored -> { });
        tx(() -> {
            var run = runs.findById(accepted.runId()).orElseThrow();
            run.setStatus(RunStatus.WAITING_APPROVAL);
            run.setWaitingNodeId("approve");
            runs.save(run);
            return null;
        });
        ApprovalEntity approval = approvals.createRequest(accepted.runId(), "approve", 1, "挂起尚未落完", "",
                List.of(session.actor()), policy(TimeoutAction.REJECT), Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> approvals.decide(approval.getId(), ApprovalDecision.APPROVE, session.actor(), "应回滚"))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(approvalRows.findById(approval.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approvalEvents.findByApprovalIdOrderByCreatedAtAsc(approval.getId())).hasSize(1);
        assertThat(tasks.findByRun(accepted.runId())).hasSize(1);
    }

    @Test
    void timeoutDecisionAlsoPersistsResumeInTheSameTransaction() throws Exception {
        SessionRef session = session(true, TimeoutAction.APPROVE);
        var accepted = suspend(session);
        ApprovalEntity approval = approval(accepted.runId());
        assertThat(approvals.expireDue(Instant.now().plusSeconds(1000))).hasSize(1);
        assertThat(approvalRows.findById(approval.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(tasks.findByRun(accepted.runId())).hasSize(2).last()
                .extracting(task -> task.kind()).isEqualTo(DispatchKind.RESUME);
    }

    @Test
    void claimedResumeCrashBeforeChangingTheRunStateFailsInsteadOfLosingTheDecision() throws Exception {
        SessionRef session = session(true);
        var accepted = suspend(session);
        approvals.decide(approval(accepted.runId()).getId(), ApprovalDecision.APPROVE, session.actor(), "批准");
        var lease = tasks.claimNext(UUID.randomUUID().toString(), Duration.ofSeconds(30)).orElseThrow();
        assertThat(lease.task().kind()).isEqualTo(DispatchKind.RESUME);
        assertThat(statusOf(accepted.runId())).isEqualTo(RunStatus.WAITING_APPROVAL);
        jdbc.update("update session_dispatch_tasks set lease_until = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), lease.taskId());
        assertThat(tasks.reapExpired(10, coordinator::reconcileExpired)).isEqualTo(1);
        assertThat(statusOf(accepted.runId())).isEqualTo(RunStatus.FAILED);
        assertThat(tasks.find(lease.taskId()).orElseThrow().status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(countingNode.count(accepted.runId())).isEqualTo(1);
        assertThat(tasks.claimNext(UUID.randomUUID().toString(), Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void suspensionReleasesLockAndAnotherWorkerResumesWithoutReplayingExecutedNodes() throws Exception {
        SessionRef session = session(true);
        var first = suspend(session);
        assertThat(lockProbe.isHeld("session:" + session.id())).isFalse();
        assertThat(countingNode.count(first.runId())).isEqualTo(1);

        var second = submit(session, "next-while-waiting", "挂起后同会话下一条");
        ManualWorker anotherWorker = manualWorker();
        anotherWorker.poll();
        await(() -> statusOf(second.runId()) == RunStatus.WAITING_APPROVAL && taskDone(second.runId()));
        assertThat(lockProbe.isHeld("session:" + session.id())).isFalse();
        assertThat(countingNode.count(second.runId())).isEqualTo(1);

        approvals.decide(approval(first.runId()).getId(), ApprovalDecision.APPROVE, session.actor(), "另一实例批准");
        anotherWorker.poll();
        await(() -> statusOf(first.runId()) == RunStatus.SUCCEEDED && taskDone(first.runId()));
        assertThat(countingNode.count(first.runId())).isEqualTo(1);
        assertThat(nodes.findByRunIdOrderByStartedAtAsc(first.runId()).stream()
                .filter(row -> row.getNodeId().equals("count"))).hasSize(1);
        assertThat(messageRows.findFirstByRunIdAndRole(first.runId(), "ASSISTANT")).isPresent();
        assertThat(statusOf(second.runId())).isEqualTo(RunStatus.WAITING_APPROVAL);
    }

    @Test
    void queuedAndWaitingCancellationCloseTheirTasksAndPreventResumeExecution() throws Exception {
        SessionRef queuedSession = session(false);
        var queued = submit(queuedSession, "queued-cancel", "排队取消");
        ManualWorker worker = manualWorker();
        assertThat(worker.worker.cancel(queued.runId(), "tester")).isTrue();
        assertThat(statusOf(queued.runId())).isEqualTo(RunStatus.CANCELED);
        assertThat(tasks.findByRun(queued.runId())).singleElement()
                .extracting(task -> task.status()).isEqualTo(DispatchStatus.CANCELED);
        assertThat(countingNode.count(queued.runId())).isZero();

        SessionRef waitingSession = session(true);
        var waiting = suspend(waitingSession);
        ApprovalEntity approval = approval(waiting.runId());
        approvals.decide(approval.getId(), ApprovalDecision.APPROVE, waitingSession.actor(), "批准后但尚未恢复");
        assertThat(worker.worker.cancel(waiting.runId(), "tester")).isTrue();
        assertThat(statusOf(waiting.runId())).isEqualTo(RunStatus.CANCELED);
        assertThat(tasks.findByRun(waiting.runId())).hasSize(2).last()
                .extracting(task -> task.status()).isEqualTo(DispatchStatus.CANCELED);
        worker.poll();
        assertThat(countingNode.count(waiting.runId())).isEqualTo(1);
        assertThat(messageRows.findFirstByRunIdAndRole(waiting.runId(), "ASSISTANT")).isEmpty();
    }

    @Test
    void cancelAndApprovalDecisionRaceKeepsTheCanceledRunClosed() throws Exception {
        SessionRef session = session(true);
        var accepted = suspend(session);
        ApprovalEntity approval = approval(accepted.runId());
        ManualWorker worker = manualWorker();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService competitors = Executors.newVirtualThreadPerTaskExecutor()) {
            var decide = competitors.submit(() -> {
                start.await();
                try {
                    approvals.decide(approval.getId(), ApprovalDecision.APPROVE, session.actor(), "并发批准");
                } catch (BizException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                }
                return null;
            });
            var cancel = competitors.submit(() -> {
                start.await();
                return worker.worker.cancel(accepted.runId(), "race-tester");
            });
            start.countDown();
            decide.get(10, TimeUnit.SECONDS);
            assertThat(cancel.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(statusOf(accepted.runId())).isEqualTo(RunStatus.CANCELED);
        assertThat(tasks.findByRun(accepted.runId()).stream()
                .filter(task -> task.kind() == DispatchKind.RESUME))
                .allSatisfy(task -> assertThat(task.status()).isEqualTo(DispatchStatus.CANCELED));
        worker.poll();
        assertThat(countingNode.count(accepted.runId())).isEqualTo(1);
        assertThat(messageRows.findFirstByRunIdAndRole(accepted.runId(), "ASSISTANT")).isEmpty();
    }

    @Test
    void resumeAndCancellationRaceHasOneTerminalOutcomeWithoutTaskRunLockDeadlock() throws Exception {
        SessionRef session = session(true);
        var accepted = suspend(session);
        approvals.decide(approval(accepted.runId()).getId(), ApprovalDecision.APPROVE, session.actor(), "批准");
        ManualWorker worker = manualWorker();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService competitors = Executors.newVirtualThreadPerTaskExecutor()) {
            var resume = competitors.submit(() -> {
                start.await();
                worker.poll();
                return null;
            });
            var cancel = competitors.submit(() -> {
                start.await();
                return worker.worker.cancel(accepted.runId(), "race-tester");
            });
            start.countDown();
            resume.get(10, TimeUnit.SECONDS);
            cancel.get(10, TimeUnit.SECONDS);
        }
        await(() -> {
            RunStatus current = statusOf(accepted.runId());
            return (current == RunStatus.CANCELED || current == RunStatus.SUCCEEDED)
                    && tasks.findByRun(accepted.runId()).stream().allMatch(task -> task.status().terminal());
        });
        assertThat(countingNode.count(accepted.runId())).isEqualTo(1);
        assertThat(lockProbe.isHeld("session:" + session.id())).isFalse();
        assertThat(checkpoints.findFirstByRunIdAndConsumedAtIsNullOrderBySeqDesc(accepted.runId())).isEmpty();
        assertThat(messageRows.findFirstByRunIdAndRole(accepted.runId(), "ASSISTANT").isPresent())
                .isEqualTo(statusOf(accepted.runId()) == RunStatus.SUCCEEDED);
    }

    private SessionRef session(boolean approval) {
        return session(approval, TimeoutAction.REJECT);
    }

    private SessionRef session(boolean approval, TimeoutAction timeout) {
        String actor = "dispatch-reviewer-" + UUID.randomUUID();
        String name = "dispatch-it-" + UUID.randomUUID();
        List<NodeDef> definitions = new ArrayList<>(List.of(
                node("start", "start", Map.of()), node("count", CountingNode.TYPE, Map.of())));
        List<EdgeDef> edges = new ArrayList<>(List.of(new EdgeDef("start", "count", null)));
        if (approval) {
            definitions.add(node("approve", "approval", Map.of("assignees", actor, "waitSeconds", 600,
                    "onTimeout", timeout.name().toLowerCase(), "onReject", "fallback", "fallback", "拒绝兜底")));
            edges.add(new EdgeDef("count", "approve", null));
            edges.add(new EdgeDef("approve", "end", null));
        } else {
            edges.add(new EdgeDef("count", "end", null));
        }
        definitions.add(node("end", "end", Map.of("outputs", Map.of("reply", "reply", "approvalStatus", "approvalStatus"))));
        workflows.register(new WorkflowDefinition(UUID.randomUUID().toString(), name, 1, definitions, edges));
        UUID user = users.getOrCreate("dispatch-user-" + UUID.randomUUID(), "调度测试").getId();
        return new SessionRef(sessions.create(user, "持久调度测试", name).getId(), actor);
    }

    private static NodeDef node(String id, String type, Map<String, Object> params) {
        return new NodeDef(id, type, id, params, Set.of(), null, null);
    }

    private SessionMessageService.Accepted submit(SessionRef session, String clientId, String content) {
        return messages.submit(session.id(), clientId, content, List.of());
    }

    private SessionMessageService.Accepted suspend(SessionRef session) throws Exception {
        var accepted = submit(session, UUID.randomUUID().toString(), "需要人工审批");
        manualWorker().poll();
        await(() -> statusOf(accepted.runId()) == RunStatus.WAITING_APPROVAL && taskDone(accepted.runId()));
        return accepted;
    }

    private ApprovalEntity approval(UUID runId) {
        return approvalRows.findByRunIdOrderByCreatedAtAsc(runId).getFirst();
    }

    private RunStatus statusOf(UUID runId) {
        return runs.findById(runId).orElseThrow().getStatus();
    }

    private boolean taskDone(UUID runId) {
        var entries = tasks.findByRun(runId);
        return !entries.isEmpty() && entries.getLast().status() == DispatchStatus.DONE;
    }

    private long runCount(UUID sessionId) {
        return jdbc.queryForObject("select count(*) from workflow_runs where session_id = ?", Long.class, sessionId);
    }

    private long taskCount(UUID sessionId) {
        return jdbc.queryForObject("select count(*) from session_dispatch_tasks where session_id = ?", Long.class, sessionId);
    }

    private <T> T tx(Supplier<T> callback) {
        return new TransactionTemplate(transactionManager).execute(transaction -> callback.get());
    }

    private static ApprovalPolicy policy(TimeoutAction timeout) {
        return new ApprovalPolicy(timeout, 1, TimeoutAction.REJECT, List.of(), RejectAction.FALLBACK, "拒绝兜底", 600);
    }

    private ManualWorker manualWorker() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        PersistentDispatchWorker worker = new PersistentDispatchWorker(tasks,
                new DispatchProperties(true, true, 2, 200, Duration.ofSeconds(30), 50), coordinator,
                approvals, approvalResults, runs, events, executor, meters);
        ManualWorker manual = new ManualWorker(worker, executor);
        manualWorkers.add(manual);
        return manual;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).as("durable dispatch did not settle within 10 seconds").isTrue();
    }

    private record SessionRef(UUID id, String actor) { }

    private record ManualWorker(PersistentDispatchWorker worker, ExecutorService executor) implements AutoCloseable {
        void poll() { worker.poll(); }

        @Override
        public void close() throws Exception {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            worker.close();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean CountingNode countingNode() { return new CountingNode(); }
        @Bean @Primary LockProbe dispatchLockProbe() { return new LockProbe(); }
    }

    static class CountingNode implements NodeExecutor {
        static final String TYPE = "dispatch-test.count";
        private final ConcurrentHashMap<UUID, AtomicInteger> counts = new ConcurrentHashMap<>();
        @Override public String type() { return TYPE; }
        @Override public NodeResult execute(NodeContext context) {
            counts.computeIfAbsent(context.runId(), ignored -> new AtomicInteger()).incrementAndGet();
            context.setVariable("reply", "durable counted");
            return NodeResult.success(Map.of("reply", "durable counted"));
        }
        int count(UUID runId) { return counts.getOrDefault(runId, new AtomicInteger()).get(); }
        void clear() { counts.clear(); }
    }

    static class LockProbe implements SessionLock {
        private final ConcurrentHashMap<String, UUID> owners = new ConcurrentHashMap<>();
        @Override public Optional<Handle> tryAcquire(String key) {
            UUID token = UUID.randomUUID();
            if (owners.putIfAbsent(key, token) != null) { return Optional.empty(); }
            return Optional.of(new Handle() {
                @Override public boolean isValid() { return token.equals(owners.get(key)); }
                @Override public void close() { owners.remove(key, token); }
            });
        }
        boolean isHeld(String key) { return owners.containsKey(key); }
        void clear() { owners.clear(); }
    }
}
