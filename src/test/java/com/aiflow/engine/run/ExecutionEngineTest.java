package com.aiflow.engine.run;

import com.aiflow.engine.budget.PermissiveBudget;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.config.SafetyProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.graph.EdgeDef;
import com.aiflow.engine.graph.GraphValidator;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.RetryPolicy;
import com.aiflow.engine.graph.SpelConditionEvaluator;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeRegistry;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.node.builtin.EndNodeExecutor;
import com.aiflow.engine.node.builtin.LlmNodeExecutor;
import com.aiflow.engine.node.builtin.StartNodeExecutor;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.safety.PermissiveSafety;
import com.aiflow.engine.tool.ToolAuthorizer;
import com.aiflow.engine.safety.StreamingSafetyGuard;
import com.aiflow.engine.provider.mock.MockChatProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionEngineTest {

    private final EngineProperties properties = EngineProperties.defaults();
    private final ProviderProperties providerProperties = ProviderProperties.defaults();
    private final InMemoryRunEventBus eventBus = new InMemoryRunEventBus(properties);
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void executesLinearWorkflowWithMockProvider() {
        ProviderRouter router = new ProviderRouter(List.of(new MockChatProvider(providerProperties)), providerProperties);
        NodeRegistry registry = registry(new StartNodeExecutor(), new LlmNodeExecutor(router, PermissiveSafety.create(), SafetyProperties.defaults(),
                PermissiveBudget.service(), PermissiveBudget.properties(),
                new StreamingSafetyGuard(PermissiveSafety.create(), SafetyProperties.defaults())), new EndNodeExecutor());
        RecordingRecorder recorder = new RecordingRecorder();

        RunResult result = engine(registry, recorder, properties).execute(chatWorkflow(null, null), request("你好"));

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(String.valueOf(result.output().get("reply"))).contains("mock");
        assertThat(recorder.finishedStatus).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(recorder.startedNodes).containsExactly("start", "llm", "end");
    }

    @Test
    void emitsSequencedEvents() {
        ProviderRouter router = new ProviderRouter(List.of(new MockChatProvider(providerProperties)), providerProperties);
        NodeRegistry registry = registry(new StartNodeExecutor(), new LlmNodeExecutor(router, PermissiveSafety.create(), SafetyProperties.defaults(),
                PermissiveBudget.service(), PermissiveBudget.properties(),
                new StreamingSafetyGuard(PermissiveSafety.create(), SafetyProperties.defaults())), new EndNodeExecutor());
        List<RunEvent> events = Collections.synchronizedList(new ArrayList<>());
        RunRequest request = request("你好");
        eventBus.subscribe(request.runId().toString(), 0, events::add);

        RunResult result = engine(registry, new RecordingRecorder(), properties)
                .execute(chatWorkflow(null, null), request);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(events).isSortedAccordingTo(Comparator.comparingLong(RunEvent::seq));
        assertThat(events).extracting(RunEvent::type)
                .contains(RunEventType.RUN_STARTED, RunEventType.NODE_STARTED, RunEventType.DELTA,
                        RunEventType.RUN_SUCCEEDED);
        assertThat(events.get(events.size() - 1).type()).isEqualTo(RunEventType.RUN_SUCCEEDED);
    }

    @Test
    void retriesFailedNodeThenSucceeds() {
        FlakyExecutor flaky = new FlakyExecutor(1);
        NodeRegistry registry = registry(new StartNodeExecutor(), flaky, new EndNodeExecutor());

        RunResult result = engine(registry, new RecordingRecorder(), properties)
                .execute(chatWorkflow(new RetryPolicy(3, 1), null), request("hi"));

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(flaky.attempts.get()).isEqualTo(2);
    }

    @Test
    void degradesWithFallbackTextWhenAllAttemptsFail() {
        NodeRegistry registry = registry(new StartNodeExecutor(), new AlwaysFailExecutor(), new EndNodeExecutor());

        RunResult result = engine(registry, new RecordingRecorder(), properties)
                .execute(chatWorkflow(new RetryPolicy(2, 1), "兜底话术"), request("hi"));

        assertThat(result.status()).isEqualTo(RunStatus.DEGRADED);
        assertThat(result.output().get("fallback")).isEqualTo("兜底话术");
    }

    @Test
    void doesNotRetryDeterministicPolicyDenial() {
        CountingFailureExecutor denied = new CountingFailureExecutor(
                ToolAuthorizer.DENIED_PREFIX + "该工具只对特定用户开放");
        NodeRegistry registry = registry(new StartNodeExecutor(), denied, new EndNodeExecutor());

        RunResult result = engine(registry, new RecordingRecorder(), properties)
                .execute(chatWorkflow(new RetryPolicy(3, 1), "兜底话术"), request("hi"));

        assertThat(denied.attempts.get())
                .as("策略拒绝是确定性的：重试只会多等一轮退避，还多留一行审计")
                .isEqualTo(1);
        assertThat(result.status()).isEqualTo(RunStatus.DEGRADED);
        assertThat(result.output().get("fallback")).isEqualTo("兜底话术");
    }

    @Test
    void failsRunWhenStepBudgetExceeded() {
        EngineProperties tiny = new EngineProperties(2, 60, 2, 200, 1000, 1_800_000, 15_000,
                "basic-chat", new String[]{"basic-chat"}, "步数兜底", 2000,
                new EngineProperties.Session(false, 30_000));
        NodeRegistry registry = registry(new StartNodeExecutor(), new FixedExecutor("delay"), new EndNodeExecutor());
        WorkflowDefinition definition = new WorkflowDefinition("wf", "chain", 1,
                List.of(new NodeDef("start", "start", "start", Map.of(), Set.of(), null, null),
                        new NodeDef("d1", "delay", "d1", Map.of(), Set.of(), null, null),
                        new NodeDef("d2", "delay", "d2", Map.of(), Set.of(), null, null),
                        new NodeDef("d3", "delay", "d3", Map.of(), Set.of(), null, null),
                        new NodeDef("end", "end", "end", Map.of(), Set.of(), null, null)),
                List.of(new EdgeDef("start", "d1", null), new EdgeDef("d1", "d2", null),
                        new EdgeDef("d2", "d3", null), new EdgeDef("d3", "end", null)));

        RunResult result = engine(registry, new RecordingRecorder(), tiny).execute(definition, request("hi"));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("步数");
        assertThat(result.output()).containsKey("fallback");
    }

    private ExecutionEngine engine(NodeRegistry registry, RunRecorder recorder, EngineProperties props) {
        return new ExecutionEngine(new GraphValidator(registry), registry, new SpelConditionEvaluator(), eventBus,
                recorder, new InMemoryCheckpointStore(), new RunCancellationRegistry(), executorService, props);
    }

    private NodeRegistry registry(NodeExecutor... executors) {
        return new NodeRegistry(List.of(executors));
    }

    private RunRequest request(String input) {
        return new RunRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Map.of("input", input));
    }

    private WorkflowDefinition chatWorkflow(RetryPolicy retry, String fallback) {
        Map<String, Object> llmParams = new LinkedHashMap<>();
        llmParams.put("prompt", "${input}");
        if (fallback != null) {
            llmParams.put("fallback", fallback);
        }
        return new WorkflowDefinition("wf", "test-flow", 1,
                List.of(new NodeDef("start", "start", "start", Map.of(), Set.of(), null, null),
                        new NodeDef("llm", "llm.chat", "llm", llmParams, Set.of("chat"), retry, null),
                        new NodeDef("end", "end", "end",
                                Map.<String, Object>of("outputs", Map.of("reply", "reply", "fallback", "fallback")),
                                Set.of(), null, null)),
                List.of(new EdgeDef("start", "llm", null), new EdgeDef("llm", "end", null)));
    }

    private static final class RecordingRecorder implements RunRecorder {

        private final List<String> startedNodes = new CopyOnWriteArrayList<>();
        private volatile RunStatus finishedStatus;
        private volatile String waitingNodeId;
        private volatile UUID suspendedNodeRunId;

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
            startedNodes.add(node.id());
            return UUID.randomUUID();
        }

        @Override
        public void finishNodeRun(UUID nodeRunId, NodeRunStatus status, Map<String, Object> output, String error,
                                  Instant endedAt) {
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
            this.suspendedNodeRunId = nodeRunId;
        }
    }

    private static final class FlakyExecutor implements NodeExecutor {

        private final int failuresBeforeSuccess;
        private final AtomicInteger attempts = new AtomicInteger();

        FlakyExecutor(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public String type() {
            return "llm.chat";
        }

        @Override
        public NodeResult execute(NodeContext context) {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                return NodeResult.failed("flaky failure " + attempt);
            }
            context.setVariable("reply", "flaky ok");
            return NodeResult.success(Map.of("reply", "flaky ok"));
        }
    }

    /** 每次执行都以固定原因失败，并数执行次数（重试与否全看这个计数）。 */
    private static final class CountingFailureExecutor implements NodeExecutor {

        private final String reason;
        private final AtomicInteger attempts = new AtomicInteger();

        CountingFailureExecutor(String reason) {
            this.reason = reason;
        }

        @Override
        public String type() {
            return "llm.chat";
        }

        @Override
        public NodeResult execute(NodeContext context) {
            attempts.incrementAndGet();
            return NodeResult.failed(reason);
        }
    }

    private static final class AlwaysFailExecutor implements NodeExecutor {

        @Override
        public String type() {
            return "llm.chat";
        }

        @Override
        public NodeResult execute(NodeContext context) {
            return NodeResult.failed("always fails");
        }
    }

    private static final class FixedExecutor implements NodeExecutor {

        private final String type;

        FixedExecutor(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public NodeResult execute(NodeContext context) {
            return NodeResult.success(Map.of());
        }
    }
}
