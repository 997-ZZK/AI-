package com.aiflow.engine.run;

import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.graph.EdgeDef;
import com.aiflow.engine.graph.GraphValidator;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.SpelConditionEvaluator;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeRegistry;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 挂起 / 恢复的引擎级语义（人工审批的底座，见 docs/approval-node-design.md §2）。
 *
 * <p>这个类钉住三条不可妥协的语义：**不重放已执行节点**、**版本不一致拒绝恢复**、
 * **步数预算跨挂起累计**。任何一条被改坏，都会以「重复扣费」「静默换版」「审批一次白送一份预算」
 * 的形式出现在生产里，而不是以编译错误的形式。
 */
class ExecutionEngineSuspensionTest {

    private static final String BEFORE = "before";
    private static final String APPROVE = "approve";
    private static final String AFTER = "after";
    private static final String EXTRA = "extra";

    private final EngineProperties properties = EngineProperties.defaults();
    private final InMemoryRunEventBus eventBus = new InMemoryRunEventBus(properties);
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void suspendsRunAndWritesCheckpoint() {
        CountingExecutor before = new CountingExecutor("test.count.before", "beforeCount");
        SuspendingExecutor approve = new SuspendingExecutor();
        CountingExecutor after = new CountingExecutor("test.count.after", "afterCount");
        RecordingRecorder recorder = new RecordingRecorder();
        List<RunEvent> events = Collections.synchronizedList(new ArrayList<>());

        RunRequest request = request();
        eventBus.subscribe(request.runId().toString(), 0, events::add);

        RunResult result = engine(registry(before, approve, after), recorder, properties)
                .execute(chain(1, BEFORE, APPROVE, AFTER), request);

        assertThat(result.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(recorder.waitingNodeId).isEqualTo(APPROVE);
        assertThat(recorder.finishedStatus)
                .as("挂起不是终态：不能调 finishRun（否则运行会被写成收尾状态）")
                .isNull();
        assertThat(recorder.suspendedNodeStatus).isEqualTo(NodeRunStatus.WAITING);
        assertThat(recorder.suspendedEndedAt)
                .as("挂起时 ended_at 必须留空：节点尚未收口")
                .isNull();
        assertThat(events).extracting(RunEvent::type)
                .contains(RunEventType.NODE_STARTED, RunEventType.NODE_SUSPENDED, RunEventType.RUN_WAITING)
                .doesNotContain(RunEventType.RUN_SUCCEEDED, RunEventType.RUN_FAILED);
        assertThat(after.executions.get()).as("挂起之后的下游节点不该执行").isZero();

        RunCheckpoint checkpoint = checkpoints.findLatestPending(request.runId()).orElseThrow().checkpoint();
        assertThat(checkpoint.nodeId()).isEqualTo(APPROVE);
        assertThat(checkpoint.nextIndex()).isEqualTo(2);
        assertThat(checkpoint.workflowVersion()).isEqualTo(1);
        assertThat(checkpoint.steps()).isEqualTo(2);
        assertThat(checkpoint.variables()).containsEntry("beforeCount", 1);
        assertThat(checkpoint.activated())
                .as("挂起节点的出边还没判定：恢复时才按决策结果激活下游")
                .containsExactlyInAnyOrder(BEFORE, APPROVE);
    }

    @Test
    void resumesAfterApprovalWithoutReplayingCompletedNodes() {
        CountingExecutor before = new CountingExecutor("test.count.before", "beforeCount");
        SuspendingExecutor approve = new SuspendingExecutor();
        CountingExecutor after = new CountingExecutor("test.count.after", "afterCount");
        RecordingRecorder recorder = new RecordingRecorder();
        ExecutionEngine engine = engine(registry(before, approve, after), recorder, properties);
        WorkflowDefinition definition = chain(1, BEFORE, APPROVE, AFTER);
        RunRequest request = request();

        RunResult suspended = engine.execute(definition, request);
        assertThat(suspended.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        RunCheckpoint checkpoint = checkpoints.findLatestPending(request.runId()).orElseThrow().checkpoint();

        RunResult resumed = engine.resume(definition, request, checkpoint,
                NodeResult.success(Map.of("approvalStatus", "APPROVED", "approvalBy", "alice")));

        assertThat(resumed.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(resumed.output()).containsEntry("approvalStatus", "APPROVED");
        assertThat(before.executions.get()).as("挂起前的节点只执行一次（重放会重复副作用）").isEqualTo(1);
        assertThat(approve.executions.get()).as("断点节点不重新执行").isEqualTo(1);
        assertThat(after.executions.get()).as("恢复后下游节点要真的跑").isEqualTo(1);
        assertThat(recorder.closedNodeRuns)
                .as("收口的是挂起时那一行 node_runs")
                .contains(recorder.suspendingNodeRunId);
        assertThat(Collections.frequency(recorder.startedNodeIds, APPROVE))
                .as("恢复不为断点节点新起一行 node_runs（一次审批 = 一行）")
                .isEqualTo(1);
        assertThat(recorder.finishedStatus).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void resumesAsDegradedWhenRejected() {
        RecordingRecorder recorder = new RecordingRecorder();
        ExecutionEngine engine = engine(registry(new CountingExecutor("test.count.before", "beforeCount"),
                new SuspendingExecutor(), new CountingExecutor("test.count.after", "afterCount")), recorder, properties);
        WorkflowDefinition definition = chain(1, BEFORE, APPROVE, AFTER);
        RunRequest request = request();

        engine.execute(definition, request);
        RunCheckpoint checkpoint = checkpoints.findLatestPending(request.runId()).orElseThrow().checkpoint();

        RunResult resumed = engine.resume(definition, request, checkpoint,
                NodeResult.degraded(Map.of("approvalStatus", "REJECTED", "fallback", "申请未通过，请联系主管"),
                        "审批被拒绝"));

        assertThat(resumed.status()).isEqualTo(RunStatus.DEGRADED);
        assertThat(resumed.output()).containsEntry("fallback", "申请未通过，请联系主管");
    }

    @Test
    void rejectsResumeWhenWorkflowVersionChanged() {
        ExecutionEngine engine = engine(registry(new CountingExecutor("test.count.before", "beforeCount"),
                new SuspendingExecutor(), new CountingExecutor("test.count.after", "afterCount")),
                new RecordingRecorder(), properties);
        RunRequest request = request();
        engine.execute(chain(1, BEFORE, APPROVE, AFTER), request);
        RunCheckpoint checkpoint = checkpoints.findLatestPending(request.runId()).orElseThrow().checkpoint();

        assertThatThrownBy(() -> engine.resume(chain(2, BEFORE, APPROVE, AFTER), request, checkpoint,
                NodeResult.success(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝恢复")
                .hasMessageContaining("版本");
    }

    @Test
    void carriesStepBudgetAcrossSuspension() {
        EngineProperties tiny = new EngineProperties(3, 60, 2, 200, 1000, 1_800_000, 15_000,
                "basic-chat", new String[]{"basic-chat"}, "步数兜底", 2000,
                new EngineProperties.Session(false, 30_000));
        ExecutionEngine engine = engine(registry(new CountingExecutor("test.count.before", "beforeCount"),
                new SuspendingExecutor(), new CountingExecutor("test.count.after", "afterCount"),
                new CountingExecutor("test.count.extra", "extraCount")), new RecordingRecorder(), tiny);
        WorkflowDefinition definition = chain(1, BEFORE, APPROVE, AFTER, EXTRA);
        RunRequest request = request();

        engine.execute(definition, request);
        RunCheckpoint checkpoint = checkpoints.findLatestPending(request.runId()).orElseThrow().checkpoint();
        assertThat(checkpoint.steps()).isEqualTo(2);

        RunResult resumed = engine.resume(definition, request, checkpoint, NodeResult.success(Map.of()));

        assertThat(resumed.status())
                .as("挂起前的 2 步必须计入预算：恢复一次不等于白送一份 maxSteps")
                .isEqualTo(RunStatus.FAILED);
        assertThat(resumed.error()).contains("步数");
    }

    @Test
    void consumedCheckpointIsNotResumableAgain() {
        ExecutionEngine engine = engine(registry(new CountingExecutor("test.count.before", "beforeCount"),
                new SuspendingExecutor(), new CountingExecutor("test.count.after", "afterCount")),
                new RecordingRecorder(), properties);
        RunRequest request = request();
        engine.execute(chain(1, BEFORE, APPROVE, AFTER), request);
        UUID runId = request.runId();

        assertThat(checkpoints.findLatestPending(runId)).isPresent();
        checkpoints.consume(checkpoints.findLatestPending(runId).orElseThrow().id());
        assertThat(checkpoints.findLatestPending(runId))
                .as("已消费的检查点不能再被恢复一次")
                .isEmpty();
    }

    private ExecutionEngine engine(NodeRegistry registry, RunRecorder recorder, EngineProperties props) {
        return new ExecutionEngine(new GraphValidator(registry), registry, new SpelConditionEvaluator(), eventBus,
                recorder, checkpoints, new RunCancellationRegistry(), executorService, props);
    }

    private NodeRegistry registry(NodeExecutor... executors) {
        return new NodeRegistry(List.of(executors));
    }

    private RunRequest request() {
        return new RunRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Map.of("input", "请审批这笔退款"));
    }

    /** 链式工作流（第一个节点是入口）；节点 id 到类型：before/after/extra 是计数节点，approve 是挂起节点。 */
    private WorkflowDefinition chain(int version, String... nodeIds) {
        List<NodeDef> nodes = new ArrayList<>();
        List<EdgeDef> edges = new ArrayList<>();
        String previous = null;
        for (String nodeId : nodeIds) {
            nodes.add(new NodeDef(nodeId, typeOf(nodeId), nodeId, Map.of(), Set.of(), null, null));
            if (previous != null) {
                edges.add(new EdgeDef(previous, nodeId, null));
            }
            previous = nodeId;
        }
        return new WorkflowDefinition("wf", "suspension-flow", version, nodes, edges);
    }

    private String typeOf(String nodeId) {
        return APPROVE.equals(nodeId) ? "test.suspend" : "test.count." + nodeId;
    }

    /** 数执行次数：重放会让它变成 2，这是「不重放」最直接的证据。 */
    private static final class CountingExecutor implements NodeExecutor {

        private final String type;
        private final String variable;
        private final AtomicInteger executions = new AtomicInteger();

        CountingExecutor(String type, String variable) {
            this.type = type;
            this.variable = variable;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public NodeResult execute(NodeContext context) {
            int count = executions.incrementAndGet();
            context.setVariable(variable, count);
            return NodeResult.success(Map.of(variable, count));
        }
    }

    private static final class SuspendingExecutor implements NodeExecutor {

        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public String type() {
            return "test.suspend";
        }

        @Override
        public NodeResult execute(NodeContext context) {
            executions.incrementAndGet();
            return NodeResult.suspend(Map.of("approvalId", "approval-" + context.node().id()));
        }
    }

    private static final class RecordingRecorder implements RunRecorder {

        private volatile RunStatus finishedStatus;
        private volatile String waitingNodeId;
        private volatile NodeRunStatus suspendedNodeStatus;
        private volatile Instant suspendedEndedAt = Instant.now();
        private volatile UUID suspendingNodeRunId;
        private final List<UUID> closedNodeRuns = new CopyOnWriteArrayList<>();
        private final List<String> startedNodeIds = new CopyOnWriteArrayList<>();

        @Override
        public UUID startRun(RunRequest request, WorkflowDefinition definition) {
            return request.runId();
        }

        @Override
        public void finishRun(UUID runId, RunStatus status, Map<String, Object> output, String error, int steps) {
            this.finishedStatus = status;
        }

        @Override
        public UUID startNodeRun(UUID runId, NodeDef node, int attempt, Map<String, Object> inputSnapshot) {
            startedNodeIds.add(node.id());
            UUID nodeRunId = UUID.randomUUID();
            if ("test.suspend".equals(node.type())) {
                this.suspendingNodeRunId = nodeRunId;
            }
            return nodeRunId;
        }

        @Override
        public void finishNodeRun(UUID nodeRunId, NodeRunStatus status, Map<String, Object> output, String error,
                                 Instant endedAt) {
            closedNodeRuns.add(nodeRunId);
        }

        @Override
        public void markWaiting(UUID runId, String nodeId) {
            this.waitingNodeId = nodeId;
        }

        @Override
        public boolean claimResume(UUID runId) {
            return true;
        }

        @Override
        public void suspendNodeRun(UUID nodeRunId, Map<String, Object> output) {
            this.suspendedNodeStatus = NodeRunStatus.WAITING;
            this.suspendedEndedAt = null;
        }
    }
}
