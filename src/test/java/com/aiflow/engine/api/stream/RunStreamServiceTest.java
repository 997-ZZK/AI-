package com.aiflow.engine.api.stream;

import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.event.EventReplayUnavailableException;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.integration.event.RedisEventProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunStreamServiceTest {
    @Test
    void slowClientHasABoundedQueueAndCannotBlockPublisher() throws Exception {
        InMemoryRunEventBus bus = new InMemoryRunEventBus(EngineProperties.defaults());
        RunStreamService service = service(bus, 2);
        BlockingEmitter emitter = new BlockingEmitter();
        service.subscribe("run", 0, emitter);
        bus.publish(event());
        assertThat(emitter.entered.await(2, TimeUnit.SECONDS)).isTrue();
        long began = System.nanoTime();
        for (int index = 0; index < 4; index++) bus.publish(event());
        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(1));
        assertThat(service.status().sseOverflow()).isEqualTo(1);
        assertThat(service.status().connectionCount()).isZero();
        assertThat(bus.status().subscriptionCount()).isZero();
        emitter.release.countDown();
        service.close();
    }

    @Test
    void replayFailureCleansConnectionAndDoesNotStartSender() {
        RunEventBus bus = mock(RunEventBus.class);
        when(bus.subscribe(anyString(), anyLong(), any(), any())).thenThrow(new EventReplayUnavailableException());
        RunStreamService service = service(bus, 2);
        assertThatThrownBy(() -> service.subscribe("run", 0)).hasMessageContaining("回放");
        assertThat(service.status().connectionCount()).isZero();
    }

    @Test
    void failedSendUnsubscribesAndDoesNotLeaveHeartbeatRegistrations() throws Exception {
        InMemoryRunEventBus bus = new InMemoryRunEventBus(EngineProperties.defaults());
        RunStreamService service = service(bus, 2);
        CountDownLatch completed = new CountDownLatch(1);
        service.subscribe("run", 0, new SseEmitter() {
            @Override public void send(SseEventBuilder event) throws IOException { throw new IOException("disconnected"); }
            @Override public void complete() { completed.countDown(); }
        });
        bus.publish(event());
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(service.status().connectionCount()).isZero();
        assertThat(bus.status().subscriptionCount()).isZero();
        service.heartbeat();
        service.close();
    }

    @Test
    void asynchronousReplayGapSendsControlWithoutRunSequenceThenClosesAndUnsubscribes() throws Exception {
        RunEventBus bus = mock(RunEventBus.class);
        AtomicReference<Consumer<RuntimeException>> errorCallback = new AtomicReference<>();
        AtomicInteger unsubscribed = new AtomicInteger();
        when(bus.subscribe(anyString(), anyLong(), any(), any())).thenAnswer(invocation -> {
            errorCallback.set(invocation.getArgument(3));
            return (RunEventBus.Subscription) unsubscribed::incrementAndGet;
        });
        RunStreamService service = service(bus, 2);
        AtomicReference<String> sent = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        service.subscribe("run", 0, new SseEmitter() {
            @Override public void send(SseEventBuilder event) {
                StringBuilder frame = new StringBuilder();
                event.build().forEach(part -> frame.append(part.getData()));
                sent.set(frame.toString());
            }
            @Override public void complete() { completed.countDown(); }
        });
        errorCallback.get().accept(new EventReplayUnavailableException());
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(sent.get()).contains("STREAM_ERROR", "REPLAY_UNAVAILABLE", "/api/v1/runs/run").doesNotContain("id:", "\"seq\"");
        assertThat(unsubscribed).hasValue(1);
        assertThat(service.status().connectionCount()).isZero();
        service.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RunEventType.class, names = {
            "RUN_SUCCEEDED", "RUN_DEGRADED", "RUN_FAILED", "RUN_CANCELED"
    })
    void terminalEventsSendOnceAndCleanUpConnection(RunEventType terminalType) throws Exception {
        InMemoryRunEventBus bus = new InMemoryRunEventBus(EngineProperties.defaults());
        RunStreamService service = service(bus, 2);
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        service.subscribe("run", 0, new SseEmitter() {
            @Override public void send(SseEventBuilder event) {
                sends.incrementAndGet();
            }

            @Override public void complete() {
                completions.incrementAndGet();
                completed.countDown();
            }
        });

        bus.publish(RunEvent.of("run", terminalType, null, Map.of("status", terminalType.name())));

        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        bus.publish(event());
        assertThat(sends).hasValue(1);
        assertThat(completions).hasValue(1);
        assertThat(service.status().connectionCount()).isZero();
        assertThat(bus.status().subscriptionCount()).isZero();
        service.close();
    }

    private static RunStreamService service(RunEventBus bus, int capacity) {
        var defaults = RedisEventProperties.defaults();
        return new RunStreamService(bus, new ObjectMapper().findAndRegisterModules(), EngineProperties.defaults(),
                new RedisEventProperties("memory", 2000, defaults.retention(), defaults.readBlock(), 256, 3, capacity));
    }
    private static RunEvent event() { return RunEvent.of("run", RunEventType.DELTA, "llm", Map.of("text", "test")); }
    private static final class BlockingEmitter extends SseEmitter {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        @Override public void send(SseEventBuilder event) throws IOException {
            entered.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { throw new IOException("closed", interrupted); }
        }
    }
}
