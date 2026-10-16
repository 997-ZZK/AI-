package com.aiflow.engine.event;

import com.aiflow.engine.config.EngineProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEventBusTest {
    @Test
    void concurrentPublicationRetainsSequenceOrderAndReplayDoesNotLeaveAWindow() throws Exception {
        InMemoryRunEventBus bus = new InMemoryRunEventBus(EngineProperties.defaults());
        List<Long> received = new CopyOnWriteArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var subscription = bus.subscribe("run", 0, event -> received.add(event.seq()));
            for (int index = 0; index < 100; index++) executor.submit(() -> bus.publish(event()));
            executor.close();
            subscription.close();
        }
        assertThat(received).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList());
        assertThat(bus.status().subscriptionCount()).isZero();
    }

    @Test
    void cursorBeyondLatestAndTrimmedHistoryAreExplicitErrorsAndFailedReplayDoesNotRegister() {
        InMemoryRunEventBus bus = new InMemoryRunEventBus(EngineProperties.defaults());
        for (int index = 0; index < 2001; index++) bus.publish(event());
        assertThatThrownBy(() -> bus.subscribe("run", 0, ignored -> {})).isInstanceOf(EventReplayUnavailableException.class);
        assertThatThrownBy(() -> bus.subscribe("run", -1, ignored -> {})).isInstanceOf(EventCursorException.class);
        assertThatThrownBy(() -> bus.subscribe("run", 2002, ignored -> {})).isInstanceOf(EventCursorException.class);
        assertThatThrownBy(() -> bus.subscribe("run", 1, ignored -> { throw new IllegalStateException("disconnect"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bus.status().subscriptionCount()).isZero();
        List<Long> replayed = new CopyOnWriteArrayList<>();
        var subscription = bus.subscribe("run", 1, replay -> replayed.add(replay.seq()));
        assertThat(replayed).hasSize(2000).first().isEqualTo(2L);
        subscription.close();
    }

    @Test
    void safePublishIsolatesOnlyTransportExceptionAndNeverFakesASequence() {
        RunEventBus unavailable = new TestBus(new EventPublishException(new IllegalStateException("transport")));
        assertThat(unavailable.publishSafely(event())).isEmpty();
        RunEventBus lostLease = new TestBus(new IllegalStateException("lease lost"));
        assertThatThrownBy(() -> lostLease.publishSafely(event())).isInstanceOf(IllegalStateException.class).hasMessage("lease lost");
    }

    private static RunEvent event() { return RunEvent.of("run", RunEventType.DELTA, "llm", Map.of("text", "synthetic")); }
    private record TestBus(RuntimeException failure) implements RunEventBus {
        public RunEvent publish(RunEvent event) { throw failure; }
        public Subscription subscribe(String runId, long fromSeq, Consumer<RunEvent> consumer) { return () -> {}; }
    }
}
