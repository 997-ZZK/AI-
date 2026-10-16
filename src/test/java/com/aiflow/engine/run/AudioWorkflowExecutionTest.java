package com.aiflow.engine.run;

import com.aiflow.engine.audio.AudioHandleStore;
import com.aiflow.engine.audio.AudioInput;
import com.aiflow.engine.audio.AudioLimits;
import com.aiflow.engine.audio.AudioOperations;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.event.RunEvent;
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
import com.aiflow.engine.node.builtin.EndNodeExecutor;
import com.aiflow.engine.node.builtin.TtsNodeExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Engine-level guard: run snapshots/events may contain only opaque handles and metadata. */
class AudioWorkflowExecutionTest {

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final EngineProperties properties = EngineProperties.defaults();
    private final InMemoryRunEventBus events = new InMemoryRunEventBus(properties);
    private final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();

    @AfterEach
    void close() {
        threads.shutdownNow();
    }

    @Test
    void terminalSuccessReleasesHandlesAndNeverRecordsAudioBytes() {
        AudioHandleStore store = new AudioHandleStore();
        RecordingRecorder recorder = new RecordingRecorder();
        RunRequest request = request();
        List<RunEvent> captured = subscribe(request);
        RunResult result = engine(store, recorder, putNode(store, false, false)).execute(workflow(), request);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(store.size()).isZero();
        assertSafe(recorder.snapshots);
        captured.forEach(event -> assertSafe(event.data()));
    }

    @Test
    void suspensionAlsoReleasesHandlesAndCheckpointContainsOnlyStrings() {
        AudioHandleStore store = new AudioHandleStore();
        RecordingRecorder recorder = new RecordingRecorder();
        RunRequest request = request();
        RunResult result = engine(store, recorder, putNode(store, true, false)).execute(workflow(), request);

        assertThat(result.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(store.size()).isZero();
        recorder.checkpointVariables = checkpoints.findLatestPending(request.runId()).orElseThrow().checkpoint().variables();
        assertThat(recorder.checkpointVariables).containsKey("audioHandle");
        assertThat(recorder.checkpointVariables.get("audioHandle")).isInstanceOf(String.class);
        assertSafe(recorder.checkpointVariables);
    }

    @Test
    void exceptionAndFailedNodeReleaseHandles() {
        AudioHandleStore store = new AudioHandleStore();
        RecordingRecorder recorder = new RecordingRecorder();
        RunResult result = engine(store, recorder, putNode(store, false, true)).execute(workflow(), request());
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(store.size()).isZero();
        assertSafe(recorder.snapshots);
    }

    @Test
    void timeoutClosesSegmentAndLateProviderCannotWriteIntoReopenedRun() throws Exception {
        AudioHandleStore store = new AudioHandleStore();
        RecordingRecorder recorder = new RecordingRecorder();
        RunRequest request = request();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> lateFailure = new AtomicReference<>();
        NodeExecutor late = new NodeExecutor() {
            @Override public String type() { return "test.audio"; }
            @Override public NodeResult execute(NodeContext context) {
                AudioHandleStore.Access access = store.access(context.runId(), context::throwIfCanceled);
                started.countDown();
                boolean released = false;
                while (!released) {
                    try { release.await(); released = true; }
                    catch (InterruptedException ignored) { /* provider deliberately ignores cancellation */ }
                }
                try {
                    store.put(access, new AudioInput(shortWav(), "audio/wav"), context::throwIfCanceled);
                } catch (RuntimeException failure) {
                    lateFailure.set(failure);
                } finally {
                    finished.countDown();
                }
                return NodeResult.success(Map.of());
            }
        };
        WorkflowDefinition timed = new WorkflowDefinition("wf", "timeout-audio-test", 1,
                List.of(new NodeDef("audio", "test.audio", "audio", Map.of(), Set.of(),
                        new com.aiflow.engine.graph.RetryPolicy(1, 0), 1)), List.of());
        try {
            var result = threads.submit(() -> engine(store, recorder, late).execute(timed, request));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(3, TimeUnit.SECONDS).status()).isEqualTo(RunStatus.FAILED);
            assertThat(store.size()).isZero();
            store.begin(request.runId());
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(lateFailure.get()).isInstanceOf(RunAttemptClosedException.class);
            assertThat(store.size()).isZero();
        } finally {
            release.countDown();
            store.end(request.runId());
        }
    }

    @Test
    void explicitCancellationReleasesExistingAudio() throws Exception {
        AudioHandleStore store = new AudioHandleStore();
        RecordingRecorder recorder = new RecordingRecorder();
        RunCancellationRegistry cancellation = new RunCancellationRegistry();
        RunRequest request = request();
        CountDownLatch started = new CountDownLatch(1);
        NodeExecutor waiting = new NodeExecutor() {
            @Override public String type() { return "test.audio"; }
            @Override public NodeResult execute(NodeContext context) throws InterruptedException {
                var access = store.access(context.runId(), context::throwIfCanceled);
                store.put(access, new AudioInput(shortWav(), "audio/wav"), context::throwIfCanceled);
                started.countDown();
                new CountDownLatch(1).await();
                return NodeResult.success(Map.of());
            }
        };
        NodeRegistry registry = new NodeRegistry(List.of(waiting, new EndNodeExecutor()));
        ExecutionEngine engine = new ExecutionEngine(new GraphValidator(registry), registry,
                new SpelConditionEvaluator(), events, recorder, checkpoints, cancellation, threads, properties,
                new RunExecutionRegistry(), List.of(store));
        var result = threads.submit(() -> engine.execute(workflow(), request));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(store.size()).isEqualTo(1);
        assertThat(cancellation.cancel(request.runId())).isTrue();
        assertThat(result.get(2, TimeUnit.SECONDS).status()).isEqualTo(RunStatus.CANCELED);
        assertThat(store.size()).isZero();
    }

    @Test
    void timedOutTtsCannotWriteDuringLaterRetryWhileRunSegmentRemainsOpen() throws Exception {
        AudioHandleStore store = new AudioHandleStore();
        RecordingRecorder recorder = new RecordingRecorder();
        RunRequest request = request();
        CountDownLatch firstProviderStarted = new CountDownLatch(1);
        CountDownLatch firstProviderRelease = new CountDownLatch(1);
        CountDownLatch firstProviderReturned = new CountDownLatch(1);
        CountDownLatch nextNodeStarted = new CountDownLatch(1);
        CountDownLatch nextNodeRelease = new CountDownLatch(1);
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        TtsProvider slow = new TtsProvider() {
            @Override public String id() { return "mock"; }
            @Override public boolean available() { return true; }
            @Override public TtsResponse synthesize(com.aiflow.engine.audio.TtsRequest ignored) {
                int call = calls.updateAndGet(value -> value + 1);
                if (call == 1) {
                    firstProviderStarted.countDown();
                    boolean released = false;
                    while (!released) {
                        try { firstProviderRelease.await(); released = true; }
                        catch (InterruptedException ignoredInterrupt) { /* intentionally ignore timeout interrupt */ }
                    }
                    firstProviderReturned.countDown();
                }
                return new TtsResponse(shortWav(), "audio/wav", "mock", "mock-tts-1", 100);
            }
        };
        AudioOperations operations = new AudioOperations(new AudioOperations.Policy(true, true,
                new AudioOperations.Channel(false, "mock", "mock-asr-1"),
                new AudioOperations.Channel(true, "mock", "mock-tts-1"), AudioLimits.defaults()),
                () -> null, () -> slow);
        TtsNodeExecutor tts = new TtsNodeExecutor(operations, store);
        NodeExecutor blockingNext = new NodeExecutor() {
            @Override public String type() { return "test.block"; }
            @Override public NodeResult execute(NodeContext context) throws InterruptedException {
                nextNodeStarted.countDown();
                nextNodeRelease.await();
                return NodeResult.success(Map.of());
            }
        };
        NodeRegistry registry = new NodeRegistry(List.of(tts, blockingNext, new EndNodeExecutor()));
        ExecutionEngine engine = new ExecutionEngine(new GraphValidator(registry), registry,
                new SpelConditionEvaluator(), events, recorder, checkpoints, new RunCancellationRegistry(), threads,
                properties, new RunExecutionRegistry(), List.of(store));
        WorkflowDefinition definition = new WorkflowDefinition("wf", "tts-retry-fence", 1,
                List.of(new NodeDef("tts", "tts.speak", "tts", Map.of(), Set.of(),
                                new com.aiflow.engine.graph.RetryPolicy(2, 0), 1),
                        new NodeDef("next", "test.block", "next", Map.of(), Set.of(), null, 30),
                        new NodeDef("end", "end", "end", Map.of("outputs", Map.of("reply", "audioHandle")), Set.of(), null, null)),
                List.of(new EdgeDef("tts", "next", null), new EdgeDef("next", "end", null)));
        var result = threads.submit(() -> engine.execute(definition, request));
        assertThat(firstProviderStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(nextNodeStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(store.size()).isEqualTo(1);
        firstProviderRelease.countDown();
        assertThat(firstProviderReturned.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(store.size()).as("旧 attempt 迟到不能把第二个句柄写入同一执行段").isEqualTo(1);
        nextNodeRelease.countDown();
        assertThat(result.get(5, TimeUnit.SECONDS).status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(store.size()).isZero();
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
    }

    private ExecutionEngine engine(AudioHandleStore store, RecordingRecorder recorder, NodeExecutor node) {
        NodeRegistry registry = new NodeRegistry(List.of(node, new EndNodeExecutor()));
        return new ExecutionEngine(new GraphValidator(registry), registry, new SpelConditionEvaluator(), events,
                recorder, checkpoints, new RunCancellationRegistry(), threads, properties,
                new RunExecutionRegistry(), List.of(store));
    }

    private List<RunEvent> subscribe(RunRequest request) {
        List<RunEvent> eventsForRun = new CopyOnWriteArrayList<>();
        events.subscribe(request.runId().toString(), 0, eventsForRun::add);
        return eventsForRun;
    }

    private WorkflowDefinition workflow() {
        return new WorkflowDefinition("wf", "audio-engine-test", 1,
                List.of(new NodeDef("audio", "test.audio", "audio", Map.of(), Set.of(), null, null),
                        new NodeDef("end", "end", "end", Map.of("outputs", Map.of("reply", "audioHandle")), Set.of(), null, null)),
                List.of(new EdgeDef("audio", "end", null)));
    }

    private NodeExecutor putNode(AudioHandleStore store, boolean suspend, boolean throwException) {
        return new NodeExecutor() {
            @Override public String type() { return "test.audio"; }

            @Override
            public NodeResult execute(NodeContext context) {
                AudioHandleStore.Access access = store.access(context.runId(), context::throwIfCanceled);
                String handle = store.put(access, new AudioInput(shortWav(), "audio/wav"), context::throwIfCanceled);
                context.setVariable("audioHandle", handle);
                if (throwException) throw new IllegalStateException("synthetic audio node failure");
                return suspend ? NodeResult.suspend(Map.of("audioHandle", handle))
                        : NodeResult.success(Map.of("audioHandle", handle, "byteLength", shortWav().length));
            }
        };
    }

    private RunRequest request() {
        return new RunRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new HashMap<>(Map.of("input", "text")));
    }

    private static byte[] shortWav() {
        int dataLength = 3_200;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLength).put(new byte[]{'W', 'A', 'V', 'E'})
                .put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLength).put(new byte[dataLength]);
        return b.array();
    }

    private void assertSafe(Object value) {
        if (value == null) return;
        String text = String.valueOf(value);
        assertThat(text).doesNotContain("RIFF", "UklGR", "TtsResponse[", "AudioInput[byteLength", "base64", "audioBytes");
        if (value instanceof Map<?, ?> map) map.values().forEach(this::assertSafe);
        if (value instanceof Iterable<?> iterable) iterable.forEach(this::assertSafe);
    }

    private static final class RecordingRecorder implements RunRecorder {
        private final List<Map<String, Object>> snapshots = new CopyOnWriteArrayList<>();
        private Map<String, Object> checkpointVariables = Map.of();

        @Override public UUID startRun(RunRequest request, WorkflowDefinition definition) { return request.runId(); }
        @Override public void finishRun(UUID runId, RunStatus status, Map<String, Object> output, String error, int steps) { snapshots.add(output); }
        @Override public UUID startNodeRun(UUID runId, NodeDef node, int attempt, Map<String, Object> inputSnapshot) {
            snapshots.add(inputSnapshot); return UUID.randomUUID();
        }
        @Override public void finishNodeRun(UUID id, NodeRunStatus status, Map<String, Object> output, String error, Instant endedAt) { snapshots.add(output); }
        @Override public void markWaiting(UUID runId, String nodeId) { }
        @Override public boolean claimResume(UUID runId) { return true; }
        @Override public void suspendNodeRun(UUID id, Map<String, Object> output) { snapshots.add(output); }
    }
}
