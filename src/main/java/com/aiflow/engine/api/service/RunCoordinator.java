package com.aiflow.engine.api.service;

import com.aiflow.engine.approval.ApprovalService;
import com.aiflow.engine.common.trace.MdcScope;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.run.CheckpointStore;
import com.aiflow.engine.run.ExecutionEngine;
import com.aiflow.engine.run.RunCheckpoint;
import com.aiflow.engine.run.RunRecorder;
import com.aiflow.engine.run.RunRequest;
import com.aiflow.engine.run.RunResult;
import com.aiflow.engine.run.RunStatus;
import com.aiflow.engine.session.SessionLock;
import com.aiflow.engine.session.DispatchTaskStore;
import com.aiflow.engine.session.DispatchLease;
import com.aiflow.engine.session.DispatchTask;
import com.aiflow.engine.session.DispatchKind;
import com.aiflow.engine.session.DispatchStatus;
import com.aiflow.engine.run.RunExecutionRegistry;
import com.aiflow.engine.run.RunExecutionGuard;
import com.aiflow.engine.run.RunLeaseLostException;
import com.aiflow.engine.persistence.repo.NodeRunRepository;
import com.aiflow.engine.node.RunCanceledException;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/**
 * 一次运行的编排：取会话锁 -> 跑引擎 -> 落助手消息；以及挂起之后的恢复（人工审批决策 / 超时）。
 *
 * <p>挂起时**主动释放**会话锁与 worker（会话不被人不在的时间冻住）；恢复时重新入队、重新取锁，
 * 保证同一会话里「恢复」与「新消息」仍然串行。见 docs/approval-node-design.md §2。
 */
@Service
public class RunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RunCoordinator.class);

    private final WorkflowService workflowService;
    private final ExecutionEngine executionEngine;
    private final MessageRepository messageRepository;
    private final WorkflowRunRepository runRepository;
    private final CheckpointStore checkpointStore;
    private final RunRecorder runRecorder;
    private final RunEventBus eventBus;
    private final SessionLock sessionLock;
    private final ApprovalService approvalService;
    private final SessionMemoryService sessionMemoryService;
    private final LongMemoryService longMemoryService;
    private final DispatchTaskStore dispatchTasks;
    private final RunExecutionRegistry executionRegistry;
    private final NodeRunRepository nodeRunRepository;
    private final Timer lockWait;
    private final ThreadLocal<SessionLock.Handle> executionLock = new ThreadLocal<>();

    public RunCoordinator(WorkflowService workflowService, ExecutionEngine executionEngine,
                          MessageRepository messageRepository, WorkflowRunRepository runRepository,
                          CheckpointStore checkpointStore, RunRecorder runRecorder, RunEventBus eventBus,
                          SessionLock sessionLock, ApprovalService approvalService,
                          SessionMemoryService sessionMemoryService, LongMemoryService longMemoryService,
                          DispatchTaskStore dispatchTasks, RunExecutionRegistry executionRegistry,
                          NodeRunRepository nodeRunRepository, MeterRegistry meters) {
        this.workflowService = workflowService;
        this.executionEngine = executionEngine;
        this.messageRepository = messageRepository;
        this.runRepository = runRepository;
        this.checkpointStore = checkpointStore;
        this.runRecorder = runRecorder;
        this.eventBus = eventBus;
        this.sessionLock = sessionLock;
        this.approvalService = approvalService;
        this.sessionMemoryService = sessionMemoryService;
        this.longMemoryService = longMemoryService;
        this.dispatchTasks = dispatchTasks;
        this.executionRegistry = executionRegistry;
        this.nodeRunRepository = nodeRunRepository;
        this.lockWait = meters.timer("aiflow.dispatch.lock.wait");
    }

    /** Returns false only before execution starts, when the Redis session lock is busy. */
    public boolean executeTask(DispatchLease lease, NodeResult resumedResult, BooleanSupplier leaseHealthy) {
        long acquisitionStarted = System.nanoTime();
        Optional<SessionLock.Handle> handle;
        try {
            handle = sessionLock.tryAcquire("session:" + lease.sessionId());
        } finally {
            lockWait.record(System.nanoTime() - acquisitionStarted, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        if (handle.isEmpty()) {
            return false;
        }
        try (SessionLock.Handle acquired = handle.get()) {
            RunExecutionGuard guard = new RunExecutionGuard() {
                @Override
                public void check() {
                    if (!leaseHealthy.getAsBoolean() || !acquired.isValid()) {
                        throw new RunLeaseLostException("执行租约已失效");
                    }
                    // The database clock, not the application host's clock, owns lease expiry.
                    DispatchTask current = dispatchTasks.guardWrite(lease, () -> dispatchTasks.find(lease.taskId())
                            .orElseThrow(() -> new RunLeaseLostException("执行任务已不存在")));
                    if (current.cancelRequestedAt() != null) {
                        throw new RunCanceledException();
                    }
                }

                @Override
                public <T> T write(Supplier<T> action) {
                    if (!leaseHealthy.getAsBoolean() || !acquired.isValid()) {
                        throw new RunLeaseLostException("会话锁已失效");
                    }
                    return dispatchTasks.guardWrite(lease, action);
                }
            };
            try (var registration = executionRegistry.register(lease.runId(), guard);
                 var binding = executionRegistry.bind(lease.runId(), registration)) {
                executionLock.set(acquired);
                WorkflowRunEntity run = runRepository.findById(lease.runId()).orElseThrow();
                try {
                    guard.check();
                    if (lease.task().kind() == DispatchKind.PROCESS) {
                        List<UUID> attachments = new java.util.ArrayList<>();
                        JsonNode input = run.getInput();
                        if (input != null && input.path("attachments").isArray()) {
                            input.path("attachments").forEach(item -> attachments.add(UUID.fromString(item.asText())));
                        }
                        process(run.getId(), run.getWorkflowId(), run.getUserId(), run.getSessionId(),
                                input == null ? "" : input.path("input").asText(""), attachments);
                    } else {
                        if (resumedResult == null) {
                            throw new IllegalStateException("恢复任务缺少审批结果");
                        }
                        resume(run.getId(), resumedResult);
                    }
                } catch (RunCanceledException canceled) {
                    cancelRunning(run.getId(), run.getSteps());
                }
            } finally {
                executionLock.remove();
            }
        }
        return true;
    }

    private Optional<SessionLock.Handle> acquire(UUID sessionId) {
        return executionLock.get() == null ? sessionLock.tryAcquire("session:" + sessionId)
                : Optional.of(() -> { });
    }

    public void process(UUID runId, UUID workflowId, UUID userId, UUID sessionId, String content,
                        List<UUID> attachmentIds) {
        Map<String, String> mdc = new LinkedHashMap<>(MdcScope.capture());
        mdc.put("runId", runId.toString());
        mdc.put("sessionId", sessionId.toString());
        mdc.put("userId", userId.toString());
        try (MdcScope scope = MdcScope.restore(mdc)) {
            Optional<SessionLock.Handle> handle = acquire(sessionId);
            if (handle.isEmpty()) {
                log.warn("session {} is locked by another instance, run {} skipped", sessionId, runId);
                return;
            }
            try (SessionLock.Handle acquired = handle.get()) {
                executionRegistry.check(runId);
                WorkflowDefinition definition = workflowService.loadById(workflowId);
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("input", content);
                if (attachmentIds != null && !attachmentIds.isEmpty()) {
                    // 变量里放 csv，不放 List：TemplateRenderer 对非字符串值走 String.valueOf，
                    // List 会渲染成 "[a, b]"，节点没法按逗号切分。
                    input.put("attachments", attachmentIds.stream()
                            .map(UUID::toString).collect(Collectors.joining(",")));
                    input.put("attachmentCount", attachmentIds.size());
                }
                RunResult result = executionEngine.execute(definition,
                        new RunRequest(runId, userId, sessionId, workflowId, input));
                if (result.status() == RunStatus.CANCELED) {
                    cleanupCanceledArtifacts(runId);
                }
                if (result.status() != RunStatus.WAITING_APPROVAL && result.status() != RunStatus.CANCELED) {
                    saveReply(sessionId, userId, runId, result.output());
                    triggerMemoryMaintenance(sessionId, userId, runId);
                }
            }
        } catch (RunLeaseLostException e) {
            throw e;
        } catch (RunCanceledException e) {
            cancelRunning(runId, 0);
        } catch (Exception e) {
            if (cancelIfRequested(runId, 0)) {
                return;
            }
            log.error("run {} failed outside engine", runId, e);
            failRun(runId, "运行编排失败", 0);
        }
    }

    /**
     * 从检查点恢复。调用方（审批服务）已经把决策落库，这里只管把执行接上。
     *
     * <p>顺序是**先取会话锁，再抢运行状态**：反过来的话，抢到状态却拿不到锁时运行会卡在 RUNNING 无人推进。
     * 抢锁失败时直接返回（本实例不推进这次恢复）；运行仍是 WAITING_APPROVAL，下一次决策/超时会再尝试。
     */
    public void resume(UUID runId, NodeResult resumedNodeResult) {
        WorkflowRunEntity run = runRepository.findById(runId).orElse(null);
        if (run == null || run.getSessionId() == null) {
            log.warn("run {} 不存在或没有会话，无法恢复", runId);
            return;
        }
        UUID sessionId = run.getSessionId();
        UUID userId = run.getUserId();
        Map<String, String> mdc = new LinkedHashMap<>(MdcScope.capture());
        mdc.put("runId", runId.toString());
        mdc.put("sessionId", sessionId.toString());
        if (userId != null) {
            mdc.put("userId", userId.toString());
        }
        try (MdcScope scope = MdcScope.restore(mdc)) {
            Optional<SessionLock.Handle> handle = acquire(sessionId);
            if (handle.isEmpty()) {
                log.warn("session {} is locked, resume of run {} skipped", sessionId, runId);
                return;
            }
            try (SessionLock.Handle acquired = handle.get()) {
                executionRegistry.check(runId);
                if (!runRecorder.claimResume(runId)) {
                    log.info("run {} 不在挂起状态（已被恢复或已取消），跳过本次恢复", runId);
                    return;
                }
                CheckpointStore.LoadedCheckpoint loaded = checkpointStore.findLatestPending(runId).orElse(null);
                if (loaded == null) {
                    log.error("run {} 没有可用检查点，无法恢复", runId);
                    failRun(runId, "运行处于挂起状态但没有检查点，无法恢复", run.getSteps());
                    return;
                }
                WorkflowDefinition definition = workflowService.loadById(run.getWorkflowId());
                RunCheckpoint checkpoint = loaded.checkpoint();
                if (definition.version() != checkpoint.workflowVersion()) {
                    checkpointStore.consume(loaded.id());
                    failRun(runId, "挂起期间工作流已更新（版本 " + checkpoint.workflowVersion() + " -> "
                            + definition.version() + "），拒绝恢复", run.getSteps());
                    return;
                }
                RunResult result = executionEngine.resume(definition,
                        new RunRequest(runId, userId, sessionId, run.getWorkflowId(), readInput(run)),
                        checkpoint, resumedNodeResult);
                checkpointStore.consume(loaded.id());
                if (result.status() == RunStatus.CANCELED) {
                    cleanupCanceledArtifacts(runId);
                }
                if (result.status() != RunStatus.WAITING_APPROVAL && result.status() != RunStatus.CANCELED) {
                    saveReply(sessionId, userId, runId, result.output());
                    triggerMemoryMaintenance(sessionId, userId, runId);
                }
            }
        } catch (RunLeaseLostException e) {
            throw e;
        } catch (RunCanceledException e) {
            cancelRunning(runId, run.getSteps());
        } catch (Exception e) {
            if (cancelIfRequested(runId, run.getSteps())) {
                return;
            }
            log.error("run {} 恢复失败", runId, e);
            failRun(runId, "恢复失败: " + e.getMessage(), run.getSteps());
        }
    }

    /**
     * 取消一个**挂起中**的运行。
     *
     * <p>取消运行中的运行走的是进程内取消表（RunCancellationRegistry），挂起中的运行不在表里 ——
     * 它没有线程在跑，只能靠状态条件更新收口。两条路径的赢家都只有一个：恢复也是 CAS 同一列，
     * 「批准」与「取消」同时到达时不会双双生效。
     *
     * <p>本类认识审批域（调用 ApprovalService 把待办置 CANCELED），因为取消必须**同时**收口运行与待办；
     * 反过来审批域不依赖本类之外的东西，不会成环。
     */
    @Transactional
    public boolean cancelWaiting(UUID runId, String actor) {
        // Worker writes always lock task -> run. Keep that order for an external cancellation too.
        dispatchTasks.lockRunTasks(runId);
        try (var administrative = executionRegistry.bindUnguarded(runId)) {
            return cancelWaitingLocked(runId, actor);
        }
    }

    private boolean cancelWaitingLocked(UUID runId, String actor) {
        if (runRepository.transitionStatus(runId, RunStatus.WAITING_APPROVAL, RunStatus.CANCELED) == 0) {
            return false;
        }
        WorkflowRunEntity run = runRepository.findById(runId).orElse(null);
        // 步数取检查点里的：挂起时引擎还没到收尾，run 行上的 steps 仍是 0，
        // 直接抄它会把「已经跑了两步才挂起」记成「一步没跑」。
        Optional<CheckpointStore.LoadedCheckpoint> pending = checkpointStore.findLatestPending(runId);
        int steps = pending.map(loaded -> loaded.checkpoint().steps())
                .orElseGet(() -> run == null ? 0 : run.getSteps());
        int canceled = approvalService.cancelForRun(runId, actor == null ? ApprovalService.SYSTEM_ACTOR : actor);
        // 断点节点行也要收口：取消之后它不能再挂着 WAITING —— 运行中取消走的是
        // 「节点行 FAILED + 取消原因」，挂起中取消用同一口径，回放面板才不会看到一个永远在等的节点。
        pending.map(loaded -> loaded.checkpoint().nodeRunId())
                .ifPresent(nodeRunId -> runRecorder.finishNodeRun(nodeRunId, NodeRunStatus.FAILED, Map.of(),
                        "审批等待期间被取消", Instant.now()));
        pending.ifPresent(checkpoint -> checkpointStore.consume(checkpoint.id()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", RunStatus.CANCELED.name());
        data.put("steps", steps);
        data.put("output", Map.of());
        data.put("error", "审批等待期间被取消");
        data.put("canceledApprovals", canceled);
        runRecorder.finishRun(runId, RunStatus.CANCELED, Map.of(), "审批等待期间被取消", steps);
        publishAfterCommit(RunEvent.of(runId.toString(), RunEventType.RUN_CANCELED, null, data));
        log.info("run {} canceled while waiting (approved/canceled approvals: {})", runId, canceled);
        return true;
    }

    private void publishAfterCommit(RunEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventBus.publishSafely(event);
                }
            });
        } else {
            eventBus.publishSafely(event);
        }
    }

    /**
     * 运行收口后触发记忆维护（**在会话锁内调，但只做提交**）：先短期摘要，再长期抽取。
     *
     * <p>两者都跑在专用执行器上、不占会话队列：用户下一句话不必等它们（见 docs/memory-design.md §3.4、
     * docs/long-memory-design.md §3.3）。这里各包一层 try/catch —— 记忆维护的任何问题都不该把一次
     * **已经成功**的运行记成失败。
     *
     * <p>顺序固定（摘要在前）：抽取的节流判据之一是「会话消息数」，与摘要是否跑完无关；反过来的话，
     * 一个失败的重试路径可能让两者互相阻塞。
     */
    private void triggerMemoryMaintenance(UUID sessionId, UUID userId, UUID runId) {
        try {
            sessionMemoryService.maybeSummarizeAsync(sessionId, userId, runId);
        } catch (RuntimeException e) {
            log.warn("run {} 的摘要触发失败（不影响运行）：{}", runId, e.toString());
        }
        try {
            longMemoryService.maybeExtractAsync(sessionId, userId, runId);
        } catch (RuntimeException e) {
            log.warn("run {} 的长期记忆抽取触发失败（不影响运行）：{}", runId, e.toString());
        }
    }

    /** 恢复被拒（工作流改版 / 检查点缺失）时的收尾：状态与事件都按终态写，不留一个永远 RUNNING 的运行。 */
    private void failRun(UUID runId, String error, int steps) {
        runRecorder.finishRun(runId, RunStatus.FAILED, Map.of(), error, steps);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", RunStatus.FAILED.name());
        data.put("steps", steps);
        data.put("output", Map.of());
        data.put("error", error);
        eventBus.publishSafely(RunEvent.of(runId.toString(), RunEventType.RUN_FAILED, null, data));
    }

    private boolean cancelIfRequested(UUID runId, int steps) {
        try {
            executionRegistry.check(runId);
            return false;
        } catch (RunCanceledException canceled) {
            cancelRunning(runId, steps);
            return true;
        }
    }

    private void cancelRunning(UUID runId, int steps) {
        executionRegistry.write(runId, () -> {
            runRecorder.finishRun(runId, RunStatus.CANCELED, Map.of(), "运行已被取消", steps);
            cleanupCanceledArtifacts(runId);
        });
        publishAfterCommit(RunEvent.of(runId.toString(), RunEventType.RUN_CANCELED, null,
                Map.of("status", RunStatus.CANCELED.name(), "steps", steps, "output", Map.of(),
                        "error", "运行已被取消")));
    }

    private void cleanupCanceledArtifacts(UUID runId) {
        executionRegistry.write(runId, () -> {
            for (var node : nodeRunRepository.findByRunIdOrderByStartedAtAsc(runId)) {
                if (node.getStatus() == NodeRunStatus.RUNNING || node.getStatus() == NodeRunStatus.WAITING) {
                    runRecorder.finishNodeRun(node.getId(), NodeRunStatus.FAILED, Map.of(),
                            "运行已被取消", Instant.now());
                }
            }
            approvalService.cancelForRun(runId, ApprovalService.SYSTEM_ACTOR);
            checkpointStore.findLatestPending(runId).ifPresent(loaded -> checkpointStore.consume(loaded.id()));
        });
    }

    private Map<String, Object> readInput(WorkflowRunEntity run) {
        JsonNode input = run.getInput();
        if (input == null || !input.isObject()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        input.fields().forEachRemaining(entry -> values.put(entry.getKey(),
                entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString()));
        return values;
    }

    private void saveReply(UUID sessionId, UUID userId, UUID runId, Map<String, Object> output) {
        String reply = pickReply(output);
        if (reply != null) {
            executionRegistry.write(runId, () -> {
                if (messageRepository.findFirstByRunIdAndRole(runId, "ASSISTANT").isEmpty()) {
                    messageRepository.save(new MessageEntity(UUID.randomUUID(), sessionId, userId, "ASSISTANT",
                            "TEXT", reply, null, runId, Instant.now()));
                }
            });
        }
    }

    /** Runs inside the store's locked recovery transaction, never under the expired owner's guard. */
    public void reconcileExpired(DispatchTask task) {
        WorkflowRunEntity run = runRepository.findById(task.runId()).orElse(null);
        if (run == null) {
            return;
        }
        if (segmentOutcomeCommitted(task, run)) {
            return;
        }
        run.setStatus(RunStatus.FAILED);
        run.setWaitingNodeId(null);
        run.setError("执行实例租约失效，执行结果不确定，未自动重放");
        run.setFinishedAt(Instant.now());
        runRepository.save(run);
        for (var node : nodeRunRepository.findByRunIdOrderByStartedAtAsc(run.getId())) {
            if (node.getStatus() == NodeRunStatus.RUNNING || node.getStatus() == NodeRunStatus.WAITING) {
                node.setStatus(NodeRunStatus.FAILED);
                node.setError(run.getError());
                node.setEndedAt(Instant.now());
                nodeRunRepository.save(node);
            }
        }
        approvalService.cancelForRun(run.getId(), ApprovalService.SYSTEM_ACTOR);
        publishAfterCommit(RunEvent.of(run.getId().toString(), RunEventType.RUN_FAILED, null,
                Map.of("status", RunStatus.FAILED.name(), "steps", run.getSteps(), "output", Map.of(),
                        "error", run.getError())));
    }

    public boolean segmentOutcomeCommitted(DispatchTask task) {
        return runRepository.findById(task.runId()).map(run -> segmentOutcomeCommitted(task, run)).orElse(false);
    }

    private boolean segmentOutcomeCommitted(DispatchTask task, WorkflowRunEntity run) {
        if (run.getStatus() == RunStatus.WAITING_APPROVAL) {
            if (task.kind() == DispatchKind.PROCESS) {
                return true;
            }
            // A claimed RESUME can crash while still at its original checkpoint. Only a newer
            // checkpoint proves that this segment reached the next approval successfully.
            return checkpointStore.findLatestPending(run.getId())
                    .map(loaded -> !loaded.id().equals(task.checkpointId())).orElse(false);
        }
        return run.getStatus() == RunStatus.SUCCEEDED || run.getStatus() == RunStatus.DEGRADED
                || run.getStatus() == RunStatus.CANCELED || run.getStatus() == RunStatus.FAILED;
    }

    public void cancelQueued(DispatchTask task) {
        WorkflowRunEntity run = runRepository.findById(task.runId()).orElse(null);
        if (run != null && run.getStatus() == RunStatus.PENDING) {
            run.setStatus(RunStatus.CANCELED);
            run.setError("排队期间取消");
            run.setFinishedAt(Instant.now());
            runRepository.save(run);
        }
    }

    private String pickReply(Map<String, Object> output) {
        Object reply = output.get("reply");
        if (reply == null) {
            reply = output.get("fallback");
        }
        return reply == null ? null : String.valueOf(reply);
    }
}
