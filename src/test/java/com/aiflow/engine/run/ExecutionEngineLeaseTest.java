package com.aiflow.engine.run;

import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventType;
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
import com.aiflow.engine.node.RunCanceledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionEngineLeaseTest {

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final RunExecutionRegistry executions = new RunExecutionRegistry();
    private final InMemoryRunEventBus events = new InMemoryRunEventBus(EngineProperties.defaults());
    private final RunRecorder recorder = mock(RunRecorder.class);
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicBoolean canceled = new AtomicBoolean();

    @AfterEach
    void close() {
        threads.shutdownNow();
    }

    @Test
    void losingLeaseStopsWaitingFutureWithoutRetryFallbackOrTerminalWrite() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        NodeExecutor node = node(context -> {
            attempts.incrementAndGet();
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw new RunCanceledException();
            }
            return NodeResult.success(Map.of());
        });
        RunRequest request = request();
        try (var registration = executions.register(request.runId(), guard())) {
            var result = threads.submit(() -> engine(node).execute(workflow(), request));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            lost.set(true);
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RunLeaseLostException.class);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(attempts).hasValue(1);
        verify(recorder, never()).finishRun(any(), any(), any(), any(), anyInt());
        verify(recorder, never()).finishNodeRun(any(), any(), any(), any(), any());
    }

    @Test
    void explicitCancellationStillRecordsCanceledTerminalState() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        NodeExecutor node = node(context -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                throw new RunCanceledException();
            }
            return NodeResult.success(Map.of());
        });
        RunRequest request = request();
        UUID nodeRunId = UUID.randomUUID();
        when(recorder.startNodeRun(eq(request.runId()), any(), anyInt(), any())).thenReturn(nodeRunId);
        try (var registration = executions.register(request.runId(), guard())) {
            var result = threads.submit(() -> engine(node).execute(workflow(), request));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            canceled.set(true);
            assertThat(result.get(2, TimeUnit.SECONDS).status()).isEqualTo(RunStatus.CANCELED);
        }
        verify(recorder).finishNodeRun(eq(nodeRunId), eq(NodeRunStatus.FAILED), any(), any(), any(Instant.class));
        verify(recorder).finishRun(eq(request.runId()), eq(RunStatus.CANCELED), any(), any(), eq(1));
    }

    @Test
    void oldFutureCannotPublishDeltaAfterOwnerRegistrationWasReplaced() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseOldNode = new CountDownLatch(1);
        CountDownLatch attemptedDelta = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        RunRequest request = request();
        List<RunEvent> capturedEvents = new CopyOnWriteArrayList<>();
        events.subscribe(request.runId().toString(), 0, capturedEvents::add);
        NodeExecutor node = node(context -> {
            started.countDown();
            boolean released = false;
            while (!released) {
                try {
                    releaseOldNode.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Deliberately emulate a provider that does not honor future.cancel(true).
                }
            }
            try {
                context.emitDelta("stale reply");
            } catch (RuntimeException e) {
                callbackFailure.set(e);
            } finally {
                attemptedDelta.countDown();
            }
            return NodeResult.success(Map.of());
        });
        RunExecutionRegistry.Registration oldRegistration = executions.register(request.runId(), guard());
        try {
            var result = threads.submit(() -> engine(node).execute(workflow(), request));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            try (var replacement = executions.register(request.runId(), guard())) {
                assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(RunLeaseLostException.class);
                releaseOldNode.countDown();
                assertThat(attemptedDelta.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(callbackFailure.get()).isInstanceOf(RunLeaseLostException.class);
            }
        } finally {
            releaseOldNode.countDown();
            oldRegistration.close();
        }
        assertThat(capturedEvents).extracting(RunEvent::type).doesNotContain(RunEventType.DELTA,
                RunEventType.RUN_SUCCEEDED, RunEventType.RUN_DEGRADED, RunEventType.RUN_FAILED);
    }

    @Test
    void nodeThrownLeaseLossBypassesFallbackAndRetries() {
        AtomicInteger attempts = new AtomicInteger();
        NodeExecutor node = node(context -> {
            attempts.incrementAndGet();
            throw new RunLeaseLostException("owner changed");
        });
        assertThatThrownBy(() -> engine(node).execute(workflow(), request()))
                .isInstanceOf(RunLeaseLostException.class);
        assertThat(attempts).hasValue(1);
        verify(recorder, never()).finishRun(any(), any(), any(), any(), anyInt());
    }

    @Test
    void interruptingRunThreadCancelsNodeFuture() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<RunResult> outcome = new AtomicReference<>();
        NodeExecutor node = node(context -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw new RunCanceledException();
            }
            return NodeResult.success(Map.of());
        });
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                outcome.set(engine(node).execute(workflow(), request()));
            } finally {
                finished.countDown();
            }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        runner.interrupt();
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(outcome.get().status()).isEqualTo(RunStatus.CANCELED);
    }

    private ExecutionEngine engine(NodeExecutor executor) {
        NodeRegistry registry = new NodeRegistry(List.of(executor));
        return new ExecutionEngine(new GraphValidator(registry), registry, new SpelConditionEvaluator(), events,
                recorder, new InMemoryCheckpointStore(), new RunCancellationRegistry(), threads,
                EngineProperties.defaults(), executions);
    }

    private WorkflowDefinition workflow() {
        return new WorkflowDefinition("wf", "lease-test", 1,
                List.of(new NodeDef("node", "test.wait", "node", Map.of("fallback", "should not run"),
                        Set.of(), new RetryPolicy(3, 1), 30)), List.of());
    }

    private RunRequest request() {
        return new RunRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of());
    }

    private RunExecutionGuard guard() {
        return new RunExecutionGuard() {
            @Override
            public void check() {
                if (lost.get()) {
                    throw new RunLeaseLostException("owner changed");
                }
                if (canceled.get()) {
                    throw new RunCanceledException();
                }
            }

            @Override
            public <T> T write(Supplier<T> action) {
                if (lost.get()) {
                    throw new RunLeaseLostException("owner changed");
                }
                return action.get();
            }
        };
    }

    private NodeExecutor node(java.util.function.Function<NodeContext, NodeResult> action) {
        return new NodeExecutor() {
            @Override
            public String type() {
                return "test.wait";
            }

            @Override
            public NodeResult execute(NodeContext context) {
                return action.apply(context);
            }
        };
    }
}
