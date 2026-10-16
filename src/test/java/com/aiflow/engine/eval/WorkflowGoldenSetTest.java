package com.aiflow.engine.eval;

import com.aiflow.engine.budget.InMemoryTokenUsageStore;
import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.api.service.RunCoordinator;
import com.aiflow.engine.api.service.SessionMessageService;
import com.aiflow.engine.api.service.WorkflowService;
import com.aiflow.engine.attachment.AttachmentStore;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.config.BudgetProperties;
import com.aiflow.engine.config.DispatchProperties;
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
import com.aiflow.engine.node.NodeRegistry;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.node.RunCanceledException;
import com.aiflow.engine.node.builtin.EndNodeExecutor;
import com.aiflow.engine.node.builtin.LlmNodeExecutor;
import com.aiflow.engine.node.builtin.StartNodeExecutor;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.ChatProvider;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.SessionRepository;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.run.ExecutionEngine;
import com.aiflow.engine.run.InMemoryCheckpointStore;
import com.aiflow.engine.run.RunAttemptClosedException;
import com.aiflow.engine.run.RunCancellationRegistry;
import com.aiflow.engine.run.RunExecutionRegistry;
import com.aiflow.engine.run.RunRecorder;
import com.aiflow.engine.run.RunRequest;
import com.aiflow.engine.run.RunResourceLifecycle;
import com.aiflow.engine.run.RunResult;
import com.aiflow.engine.run.RunStatus;
import com.aiflow.engine.safety.ContentSafety;
import com.aiflow.engine.safety.StreamingSafetyGuard;
import com.aiflow.engine.session.DispatchTaskStore;
import com.aiflow.engine.session.SessionDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.aiflow.engine.eval.OfflineGoldenSet.requireBoolean;
import static com.aiflow.engine.eval.OfflineGoldenSet.requireFields;
import static com.aiflow.engine.eval.OfflineGoldenSet.requireInt;
import static com.aiflow.engine.eval.OfflineGoldenSet.requireText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Synthetic port faults exercise the production router, LLM node and execution engine. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowGoldenSetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> TERMINAL_EVENTS = Set.of(
            "RUN_SUCCEEDED", "RUN_DEGRADED", "RUN_FAILED", "RUN_CANCELED");
    private OfflineGoldenSet golden;

    @TestFactory
    Stream<DynamicTest> workflowContracts() throws Exception {
        golden = OfflineGoldenSet.load("/eval/layer2-workflow-golden.json", "workflow", 21,
                Set.of("routing", "budget", "safety", "retry", "timeout", "cancellation", "lifecycle"));
        return golden.cases().stream().map(testCase -> DynamicTest.dynamicTest(testCase.id(),
                () -> golden.verify(testCase, () -> evaluate(testCase))));
    }

    @AfterAll
    void writeReport() throws Exception {
        if (golden != null) {
            golden.writeReport();
        }
    }

    private void evaluate(OfflineGoldenSet.Case testCase) throws Exception {
        JsonNode input = testCase.input();
        JsonNode expected = testCase.expected();
        if ("input-rejected".equals(testCase.scenario())) {
            evaluateRejectedInput(input, expected);
            return;
        }
        requireAllowedFields(input, Set.of("prompt", "providers", "initialUsed", "limit", "maxAttempts", "maxSteps",
                "fallback", "keywords", "patterns", "maxOutputChars", "streaming", "holdBackChars"),
                Set.of("prompt", "providers"));
        requireFields(expected, Set.of("status", "steps", "runOutput", "nodeOutput", "absentRunOutput",
                "providerCalls", "budgetUsed", "eventCounts", "deltaContains", "deltaExcludes", "lateRejected",
                "errorContains", "deltaEquals", "nodeRunStatuses"));
        try (Harness harness = new Harness(testCase.scenario(), input)) {
            RunResult result = harness.execute();
            assertThat(result.status().name()).isEqualTo(requireText(expected, "status"));
            assertThat(result.steps()).isEqualTo(requireInt(expected, "steps"));
            List<String> errorFragments = strings(expected, "errorContains");
            if (errorFragments.isEmpty()) {
                assertThat(result.error()).isNull();
            } else {
                assertThat(result.error()).isNotNull();
                errorFragments.forEach(fragment -> assertThat(result.error()).contains(fragment));
            }
            assertSubset(result.output(), object(expected, "runOutput"));
            assertSubset(harness.recorder.llmOutput.get(), object(expected, "nodeOutput"));
            for (String absent : strings(expected, "absentRunOutput")) {
                assertThat(result.output()).doesNotContainKey(absent);
            }
            JsonNode calls = object(expected, "providerCalls");
            assertThat(harness.providers).extracting(ScriptedProvider::id)
                    .containsExactlyElementsOf(fieldNames(calls));
            for (ScriptedProvider provider : harness.providers) {
                assertThat(provider.calls.get()).isEqualTo(requireInt(calls, provider.id()));
            }
            assertThat(harness.budget.snapshot(harness.request.userId()).used())
                    .isEqualTo(requireInt(expected, "budgetUsed"));
            String delta = harness.events.stream().filter(event -> event.type() == RunEventType.DELTA)
                    .map(event -> String.valueOf(event.data().get("text"))).reduce("", String::concat);
            strings(expected, "deltaContains").forEach(fragment -> assertThat(delta).contains(fragment));
            strings(expected, "deltaExcludes").forEach(fragment -> assertThat(delta).doesNotContain(fragment));
            if (!expected.get("deltaEquals").isNull()) {
                assertThat(delta).isEqualTo(requireText(expected, "deltaEquals"));
            }
            JsonNode counts = object(expected, "eventCounts");
            counts.fields().forEachRemaining(entry -> {
                RunEventType type = RunEventType.valueOf(entry.getKey());
                long count = harness.events.stream().filter(event -> event.type() == type).count();
                assertThat(count).isEqualTo(requireInt(counts, entry.getKey()));
            });
            assertThat(harness.lateRejected()).isEqualTo(requireBoolean(expected, "lateRejected"));
            assertNodeRunContract(harness.recorder, object(expected, "nodeRunStatuses"));
            assertTerminalContract(harness, result);
        }
    }

    private void assertNodeRunContract(RecordingRecorder recorder, JsonNode expected) {
        assertThat(recorder.finishes.keySet()).containsExactlyInAnyOrderElementsOf(recorder.starts.keySet());
        recorder.starts.keySet().forEach(nodeRunId -> assertThat(recorder.finishes.get(nodeRunId)).hasSize(1));
        assertThat(recorder.starts.values().stream().map(NodeAttempt::nodeId).distinct().toList())
                .containsExactlyInAnyOrderElementsOf(fieldNames(expected));
        expected.fieldNames().forEachRemaining(nodeId -> {
            List<String> statuses = strings(expected, nodeId);
            assertThat(statuses).isNotEmpty();
            List<Map.Entry<UUID, NodeAttempt>> attempts = recorder.starts.entrySet().stream()
                    .filter(entry -> entry.getValue().nodeId().equals(nodeId))
                    .sorted(Comparator.comparingInt(entry -> entry.getValue().attempt())).toList();
            assertThat(attempts).hasSize(statuses.size());
            for (int index = 0; index < attempts.size(); index++) {
                Map.Entry<UUID, NodeAttempt> attempt = attempts.get(index);
                assertThat(attempt.getValue().attempt()).isEqualTo(index + 1);
                assertThat(recorder.finishes.get(attempt.getKey()).getFirst().name()).isEqualTo(statuses.get(index));
            }
        });
    }

    private void evaluateRejectedInput(JsonNode input, JsonNode expected) {
        requireFields(input, Set.of("text", "maxInputChars", "keywords", "patterns"));
        requireFields(expected, Set.of("errorCode", "category", "ioCalls"));
        SafetyProperties properties = new SafetyProperties(true, requireInt(input, "maxInputChars"), 8000,
                "synthetic safety refusal", strings(input, "keywords"), "", strings(input, "patterns"), null);
        ContentSafety safety = new ContentSafety(properties, new DefaultResourceLoader());
        SessionRepository sessions = mock(SessionRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
        WorkflowService workflows = mock(WorkflowService.class);
        SessionDispatcher dispatcher = mock(SessionDispatcher.class);
        RunCoordinator coordinator = mock(RunCoordinator.class);
        AttachmentStore attachments = mock(AttachmentStore.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        DispatchTaskStore dispatchTasks = mock(DispatchTaskStore.class);
        SessionMessageService service = new SessionMessageService(sessions, messages, runs, workflows,
                dispatcher, coordinator, attachments, safety, mapper, dispatchTasks,
                DispatchProperties.disabled(), EngineProperties.defaults());
        BizException denied = catchThrowableOfType(() -> service.submit(UUID.randomUUID(), "synthetic-message",
                requireText(input, "text"), List.of(UUID.randomUUID())), BizException.class);
        assertThat(denied).isNotNull();
        assertThat(denied.getErrorCode()).isEqualTo(ErrorCode.valueOf(requireText(expected, "errorCode")));
        assertThat(denied.getMessage()).contains("（" + requireText(expected, "category") + "）");
        assertThat(requireInt(expected, "ioCalls")).isZero();
        verifyNoInteractions(sessions, messages, runs, workflows, dispatcher, coordinator, attachments, mapper,
                dispatchTasks);
    }

    private void assertTerminalContract(Harness harness, RunResult result) {
        assertThat(harness.events).isNotEmpty();
        for (int index = 0; index < harness.events.size(); index++) {
            assertThat(harness.events.get(index).seq()).isEqualTo(index + 1L);
            assertThat(harness.events.get(index).runId()).isEqualTo(harness.request.runId().toString());
        }
        assertThat(harness.events.getFirst().type()).isEqualTo(RunEventType.RUN_STARTED);
        assertThat(harness.events.getLast().type().name()).isEqualTo("RUN_" + result.status().name());
        assertThat(harness.events.stream().filter(event -> TERMINAL_EVENTS.contains(event.type().name())).count())
                .isEqualTo(1);
        assertThat(harness.recorder.finishedStatus.get()).isEqualTo(result.status());
        assertThat(harness.recorder.finishedError.get()).isEqualTo(result.error());
        assertThat(harness.recorder.finishedRuns.get()).isEqualTo(1);
        assertThat(harness.resources.begins.get()).isEqualTo(1);
        assertThat(harness.resources.ends.get()).isEqualTo(1);
        assertThat(harness.resources.active.get()).isNull();
        assertThat(harness.cancellation.cancel(harness.request.runId())).isFalse();
    }

    private static void assertSubset(Map<String, Object> actual, JsonNode expected) {
        assertThat(actual).isNotNull();
        JsonNode captured = MAPPER.valueToTree(actual);
        expected.fields().forEachRemaining(entry -> {
            JsonNode observed = captured.get(entry.getKey());
            assertThat(observed).as("field %s", entry.getKey()).isNotNull();
            if (entry.getValue().isIntegralNumber()) {
                assertThat(observed.isIntegralNumber()).isTrue();
                assertThat(observed.longValue()).isEqualTo(entry.getValue().longValue());
            } else {
                assertThat(observed).isEqualTo(entry.getValue());
            }
        });
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Expected object field");
        }
        return value;
    }

    private static List<String> strings(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("Expected string array field");
        }
        List<String> values = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Expected string array item");
            }
            values.add(item.textValue());
        });
        return List.copyOf(values);
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void requireAllowedFields(JsonNode input, Set<String> allowed, Set<String> required) {
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("Expected contract object");
        }
        List<String> present = fieldNames(input);
        if (!allowed.containsAll(present) || !present.containsAll(required)) {
            throw new IllegalArgumentException("Unknown or missing contract field");
        }
    }

    private static int optionalInt(JsonNode input, String field, int defaultValue) {
        return input.has(field) ? requireInt(input, field) : defaultValue;
    }

    private static final class Harness implements AutoCloseable {
        private final String scenario;
        private final List<ScriptedProvider> providers;
        private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        private final RecordingRecorder recorder = new RecordingRecorder();
        private final RecordingResources resources = new RecordingResources();
        private final RunCancellationRegistry cancellation = new RunCancellationRegistry();
        private final List<RunEvent> events = new CopyOnWriteArrayList<>();
        private final RunRequest request;
        private final TokenBudgetService budget;
        private final ExecutionEngine engine;
        private final WorkflowDefinition workflow;

        private Harness(String scenario, JsonNode input) {
            if (!Set.of("chat", "timeout-late", "cancel-blocked", "step-limit").contains(scenario)) {
                throw new IllegalArgumentException("Unknown workflow scenario");
            }
            this.scenario = scenario;
            request = new RunRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Map.of("input", requireText(input, "prompt")));
            JsonNode providerPlans = input.get("providers");
            if (providerPlans == null || !providerPlans.isArray() || providerPlans.isEmpty()) {
                throw new IllegalArgumentException("Expected provider plans");
            }
            List<ScriptedProvider> scripted = new ArrayList<>();
            providerPlans.forEach(plan -> scripted.add(new ScriptedProvider(plan)));
            providers = List.copyOf(scripted);
            if (providers.stream().map(ScriptedProvider::id).distinct().count() != providers.size()) {
                throw new IllegalArgumentException("Duplicate scripted provider");
            }
            ProviderProperties defaults = ProviderProperties.defaults();
            ProviderProperties providerProperties = new ProviderProperties("auto",
                    providers.stream().map(ScriptedProvider::id).toArray(String[]::new), List.of("mock"),
                    defaults.mock(), Map.of(), defaults.probe(), defaults.breaker());
            ProviderRouter router = new ProviderRouter(new ArrayList<>(providers), providerProperties);
            BudgetProperties budgetProperties = new BudgetProperties(true, optionalInt(input, "limit", 1000), 0.8,
                    "UTC", "synthetic budget refusal", "memory");
            budget = new TokenBudgetService(new InMemoryTokenUsageStore(), budgetProperties);
            budget.charge(request.userId(), optionalInt(input, "initialUsed", 0));
            SafetyProperties safetyProperties = new SafetyProperties(true, 4000,
                    optionalInt(input, "maxOutputChars", 8000), "synthetic safety refusal",
                    input.has("keywords") ? strings(input, "keywords") : List.of(), "",
                    input.has("patterns") ? strings(input, "patterns") : List.of(),
                    new SafetyProperties.Streaming(!input.has("streaming") || requireBoolean(input, "streaming"),
                            optionalInt(input, "holdBackChars", 1), true, 200));
            ContentSafety safety = new ContentSafety(safetyProperties, new DefaultResourceLoader());
            LlmNodeExecutor llm = new LlmNodeExecutor(router, safety, safetyProperties, budget, budgetProperties,
                    new StreamingSafetyGuard(safety, safetyProperties));
            NodeRegistry registry = new NodeRegistry(List.of(new StartNodeExecutor(), llm, new EndNodeExecutor()));
            EngineProperties defaultsEngine = EngineProperties.defaults();
            EngineProperties engineProperties = new EngineProperties(optionalInt(input, "maxSteps", 50), 5, 1, 0,
                    defaultsEngine.sessionQueueCapacity(), defaultsEngine.sseTimeoutMs(),
                    defaultsEngine.sseHeartbeatMs(), "golden-chat", new String[]{"golden-chat"},
                    "synthetic engine fallback", defaultsEngine.eventBufferSize(), defaultsEngine.session());
            InMemoryRunEventBus eventBus = new InMemoryRunEventBus(engineProperties);
            eventBus.subscribe(request.runId().toString(), 0, events::add);
            engine = new ExecutionEngine(new GraphValidator(registry), registry, new SpelConditionEvaluator(),
                    eventBus, recorder, new InMemoryCheckpointStore(), cancellation, threads, engineProperties,
                    new RunExecutionRegistry(), List.of(resources));
            Map<String, Object> llmParams = new LinkedHashMap<>();
            llmParams.put("prompt", "${input}");
            if (input.has("fallback")) {
                llmParams.put("fallback", requireText(input, "fallback"));
            }
            Map<String, String> outputs = new LinkedHashMap<>();
            for (String key : List.of("reply", "fallback", "blocked", "blockCategory", "budgetStatus",
                    "budgetUsed", "budgetLimit", "budgetBlocked", "tokensUsed")) {
                outputs.put(key, key);
            }
            workflow = new WorkflowDefinition("golden-workflow", "golden-chat", 1,
                    List.of(new NodeDef("start", "start", "start", Map.of(), Set.of(), null, null),
                            new NodeDef("llm", "llm.chat", "llm", llmParams, Set.of(Capabilities.CHAT),
                                    new RetryPolicy(optionalInt(input, "maxAttempts", 1), 0),
                                    "timeout-late".equals(scenario) ? 1 : 5),
                            new NodeDef("end", "end", "end", Map.of("outputs", outputs), Set.of(), null, null)),
                    List.of(new EdgeDef("start", "llm", null), new EdgeDef("llm", "end", null)));
        }

        private RunResult execute() throws Exception {
            if ("chat".equals(scenario) || "step-limit".equals(scenario)) {
                return engine.execute(workflow, request);
            }
            var execution = threads.submit(() -> engine.execute(workflow, request));
            ScriptedProvider blocked = providers.getFirst();
            assertThat(blocked.started.await(2, TimeUnit.SECONDS)).isTrue();
            if ("cancel-blocked".equals(scenario)) {
                assertThat(cancellation.cancel(request.runId())).isTrue();
            }
            RunResult result = execution.get(4, TimeUnit.SECONDS);
            int terminalEventCount = events.size();
            blocked.release.countDown();
            assertThat(blocked.completed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(events).hasSize(terminalEventCount);
            return result;
        }

        private boolean lateRejected() {
            return providers.stream().anyMatch(provider -> provider.callbackFailure.get()
                    instanceof RunAttemptClosedException);
        }

        @Override
        public void close() {
            providers.forEach(provider -> provider.release.countDown());
            threads.shutdownNow();
        }
    }

    /** Port inputs are independent of golden expectations; no expected text is used to generate a result. */
    private static final class ScriptedProvider implements ChatProvider {
        private final String id;
        private final boolean healthy;
        private final Set<String> capabilities;
        private final List<JsonNode> actions;
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

        private ScriptedProvider(JsonNode plan) {
            requireFields(plan, Set.of("id", "healthy", "capabilities", "actions"));
            id = requireText(plan, "id");
            healthy = requireBoolean(plan, "healthy");
            capabilities = Set.copyOf(strings(plan, "capabilities"));
            JsonNode sequence = plan.get("actions");
            if (sequence == null || !sequence.isArray() || sequence.isEmpty()) {
                throw new IllegalArgumentException("Expected scripted actions");
            }
            List<JsonNode> parsed = new ArrayList<>();
            sequence.forEach(action -> {
                String mode = requireText(action, "mode");
                if (!Set.of("respond", "fail", "partial-fail", "block").contains(mode)) {
                    throw new IllegalArgumentException("Unknown provider action");
                }
                Set<String> required = switch (mode) {
                    case "fail" -> Set.of("mode");
                    case "partial-fail" -> Set.of("mode", "chunks");
                    case "respond" -> Set.of("mode", "chunks", "promptTokens", "completionTokens");
                    case "block" -> Set.of("mode", "chunks", "promptTokens", "completionTokens", "ignoreInterrupt");
                    default -> throw new IllegalArgumentException("Unknown provider action");
                };
                requireFields(action, required);
                parsed.add(action);
            });
            actions = List.copyOf(parsed);
        }

        @Override public String id() { return id; }
        @Override public boolean healthy() { return healthy; }
        @Override public Set<String> capabilities() { return capabilities; }

        @Override
        public ChatResponse chat(ChatRequest request, Consumer<String> sink) {
            int call = calls.getAndIncrement();
            JsonNode action = actions.get(Math.min(call, actions.size() - 1));
            String mode = requireText(action, "mode");
            try {
                if ("block".equals(mode)) {
                    started.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            release.await();
                            released = true;
                        } catch (InterruptedException interrupted) {
                            if (!requireBoolean(action, "ignoreInterrupt")) {
                                throw new RunCanceledException();
                            }
                        }
                    }
                }
                if ("fail".equals(mode)) {
                    throw new IllegalStateException("synthetic provider unavailable");
                }
                StringBuilder answer = new StringBuilder();
                for (String chunk : strings(action, "chunks")) {
                    answer.append(chunk);
                    try {
                        sink.accept(chunk);
                    } catch (RuntimeException failure) {
                        callbackFailure.set(failure);
                        throw failure;
                    }
                }
                if ("partial-fail".equals(mode)) {
                    throw new IllegalStateException("synthetic stream interrupted");
                }
                return new ChatResponse(answer.toString(), "synthetic-" + id,
                        requireInt(action, "promptTokens"), requireInt(action, "completionTokens"));
            } finally {
                completed.countDown();
            }
        }
    }

    private static final class RecordingRecorder implements RunRecorder {
        private final Map<UUID, NodeAttempt> starts = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<UUID, List<NodeRunStatus>> finishes = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicReference<Map<String, Object>> llmOutput = new AtomicReference<>(Map.of());
        private final AtomicInteger finishedRuns = new AtomicInteger();
        private final AtomicReference<RunStatus> finishedStatus = new AtomicReference<>();
        private final AtomicReference<String> finishedError = new AtomicReference<>();

        @Override public UUID startRun(RunRequest request, WorkflowDefinition definition) { return request.runId(); }
        @Override public void finishRun(UUID runId, RunStatus status, Map<String, Object> output, String error, int steps) {
            finishedRuns.incrementAndGet();
            finishedStatus.set(status);
            finishedError.set(error);
        }
        @Override public UUID startNodeRun(UUID runId, NodeDef node, int attempt, Map<String, Object> inputSnapshot) {
            UUID nodeRunId = UUID.randomUUID();
            starts.put(nodeRunId, new NodeAttempt(node.id(), attempt));
            return nodeRunId;
        }
        @Override public void finishNodeRun(UUID nodeRunId, NodeRunStatus status, Map<String, Object> output,
                                            String error, Instant endedAt) {
            finishes.computeIfAbsent(nodeRunId, ignored -> new CopyOnWriteArrayList<>()).add(status);
            NodeAttempt started = starts.get(nodeRunId);
            if (started != null && "llm".equals(started.nodeId())) {
                llmOutput.set(Map.copyOf(output));
            }
        }
        @Override public void markWaiting(UUID runId, String nodeId) { throw new AssertionError("Unexpected suspension"); }
        @Override public boolean claimResume(UUID runId) { throw new AssertionError("Unexpected resume"); }
        @Override public void suspendNodeRun(UUID nodeRunId, Map<String, Object> output) {
            throw new AssertionError("Unexpected suspension");
        }
    }

    private record NodeAttempt(String nodeId, int attempt) { }

    private static final class RecordingResources implements RunResourceLifecycle {
        private final AtomicInteger begins = new AtomicInteger();
        private final AtomicInteger ends = new AtomicInteger();
        private final AtomicReference<UUID> active = new AtomicReference<>();

        @Override public void begin(UUID runId) {
            assertThat(active.compareAndSet(null, runId)).isTrue();
            begins.incrementAndGet();
        }
        @Override public void end(UUID runId) {
            assertThat(active.compareAndSet(runId, null)).isTrue();
            ends.incrementAndGet();
        }
    }
}
