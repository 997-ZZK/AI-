package com.aiflow.engine.integration.event;

import com.aiflow.engine.event.EventCursorException;
import com.aiflow.engine.event.EventPublishException;
import com.aiflow.engine.event.EventReplayUnavailableException;
import com.aiflow.engine.event.EventStreamReadException;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in, real Redis only. Random test run ids; no key deletion or production-key mutation. */
@EnabledIfSystemProperty(named = "aiflow.redis.integration", matches = "true")
class RedisRunEventBusIntegrationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisRunEventBus first;
    private RedisRunEventBus second;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final String runId = "test-events-" + UUID.randomUUID();

    @BeforeEach
    void start() {
        factory = new LettuceConnectionFactory(System.getProperty("aiflow.redis.host", "localhost"),
                Integer.getInteger("aiflow.redis.port", 16379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        first = bus(Duration.ofHours(24));
        second = bus(Duration.ofHours(24));
        try (var connection = redis.getConnectionFactory().getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @AfterEach
    void stop() {
        if (first != null) first.close();
        if (second != null) second.close();
        if (factory != null) factory.destroy();
    }

    @Test
    void crossInstanceBroadcastConcurrentPublishAndReconnectHaveNoDuplicateOrGap() throws Exception {
        List<Long> atFirst = new CopyOnWriteArrayList<>();
        List<Long> atSecond = new CopyOnWriteArrayList<>();
        CountDownLatch firstBatch = new CountDownLatch(100);
        CountDownLatch firstInstanceBatch = new CountDownLatch(100);
        var a = first.subscribe(runId, 0, event -> { atFirst.add(event.seq()); firstInstanceBatch.countDown(); });
        var b = second.subscribe(runId, 0, event -> { atSecond.add(event.seq()); firstBatch.countDown(); });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int index = 0; index < 100; index++) {
                RedisRunEventBus publisher = index % 2 == 0 ? first : second;
                futures.add(executor.submit(() -> publisher.publish(event())));
            }
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertThat(firstBatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(firstInstanceBatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(atFirst).containsExactlyElementsOf(sequence(1, 100));
        b.close();
        List<Long> resumed = new CopyOnWriteArrayList<>();
        CountDownLatch realtime = new CountDownLatch(21);
        var resumedSubscription = second.subscribe(runId, 80, event -> { resumed.add(event.seq()); realtime.countDown(); });
        first.publish(event());
        assertThat(realtime.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(atSecond).containsExactlyElementsOf(sequence(1, 100));
        assertThat(resumed).containsExactlyElementsOf(sequence(81, 101));
        a.close();
        resumedSubscription.close();
        assertThat(first.status().readerCount()).isZero();
        assertThat(second.status().subscriptionCount()).isZero();
    }

    @Test
    void exactRetentionAndFutureOrTrimmedCursorsAreEnforced() {
        for (int index = 0; index < 2005; index++) first.publish(event());
        assertThat(redis.opsForStream().size(RedisRunEventBus.streamKey(runId))).isEqualTo(2000);
        assertThat(redis.getExpire(RedisRunEventBus.streamKey(runId), TimeUnit.SECONDS)).isBetween(86_390L, 86_400L);
        assertThat(redis.getExpire(RedisRunEventBus.sequenceKey(runId))).isEqualTo(-1L);
        assertThatThrownBy(() -> second.subscribe(runId, 0, ignored -> {})).isInstanceOf(EventReplayUnavailableException.class);
        assertThatThrownBy(() -> second.subscribe(runId, 2006, ignored -> {})).isInstanceOf(EventCursorException.class);
        List<Long> events = new CopyOnWriteArrayList<>();
        var subscription = second.subscribe(runId, 5, event -> events.add(event.seq()));
        assertThat(events).containsExactlyElementsOf(sequence(6, 2005));
        subscription.close();
    }

    @Test
    void expiredStreamIsExplicitAndSequenceSurvivesLaterApprovalPublication() throws Exception {
        first.close();
        first = bus(Duration.ofMillis(150));
        assertThat(first.publish(event()).seq()).isEqualTo(1);
        Thread.sleep(200);
        assertThatThrownBy(() -> second.subscribe(runId, 1, ignored -> {})).isInstanceOf(EventReplayUnavailableException.class);
        assertThat(first.publish(event()).seq()).isEqualTo(2);
        assertThatThrownBy(() -> second.subscribe(runId, 0, ignored -> {})).isInstanceOf(EventReplayUnavailableException.class);
        List<Long> resumed = new CopyOnWriteArrayList<>();
        var subscription = second.subscribe(runId, 1, event -> resumed.add(event.seq()));
        assertThat(resumed).containsExactly(2L);
        subscription.close();
    }

    @Test
    void failedPublicationDoesNotPretendSuccessOrResetCounterAndReadRetriesAreBounded() throws Exception {
        first.publish(event());
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        CountDownLatch readFailed = new CountDownLatch(1);
        second.subscribe(runId, 1, ignored -> {}, error -> { failure.set(error); readFailed.countDown(); });
        // Controlled corruption of this test's random stream. SET replaces its type; TTL bounds the fault.
        redis.opsForValue().set(RedisRunEventBus.streamKey(runId), "test-fault", Duration.ofSeconds(10));
        assertThatThrownBy(() -> first.publish(event())).isInstanceOf(EventPublishException.class);
        assertThat(first.publishSafely(event())).isEmpty();
        assertThat(redis.opsForValue().get(RedisRunEventBus.sequenceKey(runId))).isEqualTo("1");
        assertThat(first.status().publishFailures()).isEqualTo(2);
        assertThat(readFailed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isInstanceOf(EventStreamReadException.class);
        assertThat(second.status().readerCount()).isZero();
        assertThat(second.status().readFailures()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void counterGapAndConsumerFailureCannotCreateASuccessfulPartialReplay() {
        first.publish(event());
        redis.opsForValue().increment(RedisRunEventBus.sequenceKey(runId));
        assertThatThrownBy(() -> second.subscribe(runId, 0, ignored -> {})).isInstanceOf(EventReplayUnavailableException.class);
        assertThat(second.status().readerCount()).isZero();
        String otherRun = runId + "-disconnect";
        first.publish(RunEvent.of(otherRun, RunEventType.DELTA, null, Map.of()));
        assertThatThrownBy(() -> second.subscribe(otherRun, 0, ignored -> { throw new IllegalStateException("closed"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(second.status().subscriptionCount()).isZero();
    }

    private RedisRunEventBus bus(Duration retention) {
        return new RedisRunEventBus(redis, mapper,
                new RedisEventProperties("redis", 2000, retention, Duration.ofMillis(100), 256, 3, 2048));
    }
    private RunEvent event() { return RunEvent.of(runId, RunEventType.DELTA, "llm", Map.of("text", "synthetic")); }
    private static List<Long> sequence(long start, long end) { return java.util.stream.LongStream.rangeClosed(start, end).boxed().toList(); }
}
