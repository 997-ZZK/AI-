package com.aiflow.engine.integration.event;

import com.aiflow.engine.event.EventStreamReadException;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Deterministic reader lifecycle checks without a Redis server. */
@SuppressWarnings({"unchecked", "rawtypes"})
class RedisRunEventBusTest {
    @Test
    void closingLastSubscriptionDuringXreadDoesNotCountOrNotifyAReadFailure() throws Exception {
        Fixture fixture = new Fixture(3);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        doAnswer(invocation -> interruptedRead(entered, reader)).when(fixture.streams)
                .xRead(any(StreamReadOptions.class), any(StreamOffset.class));
        AtomicInteger errors = new AtomicInteger();
        try (var bus = fixture.bus) {
            var subscription = bus.subscribe("synthetic-close-xread", 0, ignored -> {}, ignored -> errors.incrementAndGet());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.close();
            awaitStopped(reader);
            assertThat(bus.status().readFailures()).isZero();
            assertThat(bus.status().readerCount()).isZero();
            assertThat(bus.status().subscriptionCount()).isZero();
            assertThat(errors).hasValue(0);
            verify(fixture.connection).close();
        }
    }

    @Test
    void closingDuringIdleSnapshotDoesNotCountOrNotifyAReadFailure() throws Exception {
        Fixture fixture = new Fixture(3);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        AtomicInteger snapshots = new AtomicInteger();
        when(fixture.redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenAnswer(invocation -> snapshots.incrementAndGet() == 1
                        ? emptySnapshot() : interruptedRead(entered, reader));
        when(fixture.streams.xRead(any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(List.of());
        AtomicInteger errors = new AtomicInteger();
        try (var bus = fixture.bus) {
            var subscription = bus.subscribe("synthetic-close-snapshot", 0, ignored -> {}, ignored -> errors.incrementAndGet());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.close();
            awaitStopped(reader);
            assertThat(bus.status().readFailures()).isZero();
            assertThat(bus.status().readerCount()).isZero();
            assertThat(errors).hasValue(0);
            assertThat(snapshots).hasValue(2);
            verify(fixture.connection).close();
        }
    }

    @Test
    void activeXreadFailureStillCountsEveryRetryAndNotifiesOnce() throws Exception {
        Fixture fixture = new Fixture(3);
        AtomicReference<Thread> reader = new AtomicReference<>();
        doAnswer(invocation -> {
            reader.set(Thread.currentThread());
            throw new RedisSystemException("synthetic active read failure", new IllegalStateException("test"));
        }).when(fixture.streams).xRead(any(StreamReadOptions.class), any(StreamOffset.class));
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        try (var bus = fixture.bus) {
            bus.subscribe("synthetic-active-xread", 0, ignored -> {}, failure -> {
                error.set(failure);
                errors.incrementAndGet();
                failed.countDown();
            });
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
            awaitStopped(reader);
            assertThat(bus.status().readFailures()).isEqualTo(3);
            assertThat(bus.status().readerCount()).isZero();
            assertThat(errors).hasValue(1);
            assertThat(error.get()).isInstanceOf(EventStreamReadException.class);
            verify(fixture.factory, times(3)).getConnection();
            verify(fixture.connection, times(3)).close();
        }
    }

    @Test
    void activeIdleSnapshotFailureIsCountedOncePerAttempt() throws Exception {
        Fixture fixture = new Fixture(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        AtomicInteger snapshots = new AtomicInteger();
        when(fixture.redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenAnswer(invocation -> {
            if (snapshots.incrementAndGet() == 1) return emptySnapshot();
            reader.set(Thread.currentThread());
            throw new RedisSystemException("synthetic active snapshot failure", new IllegalStateException("test"));
        });
        when(fixture.streams.xRead(any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(List.of());
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try (var bus = fixture.bus) {
            bus.subscribe("synthetic-active-snapshot", 0, ignored -> {}, failure -> {
                errors.incrementAndGet();
                failed.countDown();
            });
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
            awaitStopped(reader);
            assertThat(bus.status().readFailures()).isEqualTo(1);
            assertThat(errors).hasValue(1);
            assertThat(snapshots).hasValue(2);
            verify(fixture.connection).close();
        }
    }

    @Test
    void idlePollsAndDeliveredEventsReuseOneConnectionAndAdvanceTheByteCursor() throws Exception {
        Fixture fixture = new Fixture(3);
        String runId = "synthetic-reused-reader";
        ByteRecord event = fixture.record(runId, 1, "synthetic 中文");
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        AtomicReference<RunEvent> delivered = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        List<String> offsets = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            StreamReadOptions options = invocation.getArgument(0);
            StreamOffset<byte[]> offset = invocation.getArgument(1);
            assertThat(options.getCount()).isEqualTo(256L);
            assertThat(options.getBlock()).isEqualTo(100L);
            assertThat(offset.getKey()).containsExactly(StringRedisSerializer.UTF_8.serialize(RedisRunEventBus.streamKey(runId)));
            offsets.add(offset.getOffset().getOffset());
            return switch (reads.incrementAndGet()) {
                case 1 -> List.of();
                case 2 -> List.of(event);
                default -> interruptedRead(waiting, reader);
            };
        }).when(fixture.streams).xRead(any(StreamReadOptions.class), any(StreamOffset.class));
        AtomicInteger errors = new AtomicInteger();
        try (var bus = fixture.bus) {
            var subscription = bus.subscribe(runId, 0, delivered::set, ignored -> errors.incrementAndGet());
            assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(delivered.get().seq()).isEqualTo(1);
            assertThat(delivered.get().data()).containsEntry("text", "synthetic 中文");
            assertThat(offsets).containsExactly("0-0", "0-0", "1-0");
            verify(fixture.factory).getConnection();
            verify(fixture.connection, never()).close();
            // The first subscribe snapshot plus the idle check still use the template's shared path.
            verify(fixture.redis, times(2)).execute(any(RedisScript.class), anyList(), anyString(), anyString());
            subscription.close();
            awaitStopped(reader);
            verify(fixture.connection).close();
            assertThat(bus.status().readFailures()).isZero();
            assertThat(errors).hasValue(0);
        }
    }

    @Test
    void subscribersOfOneRunShareAConnectionUntilTheLastSubscriptionCloses() throws Exception {
        Fixture fixture = new Fixture(3);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        doAnswer(invocation -> interruptedRead(entered, reader)).when(fixture.streams)
                .xRead(any(StreamReadOptions.class), any(StreamOffset.class));
        try (var bus = fixture.bus) {
            var first = bus.subscribe("synthetic-shared-reader", 0, ignored -> {});
            var second = bus.subscribe("synthetic-shared-reader", 0, ignored -> {});
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            first.close();
            assertThat(bus.status().subscriptionCount()).isEqualTo(1);
            assertThat(bus.status().readerCount()).isEqualTo(1);
            verify(fixture.factory).getConnection();
            verify(fixture.connection, never()).close();
            second.close();
            awaitStopped(reader);
            verify(fixture.connection).close();
            assertThat(bus.status().readFailures()).isZero();
        }
    }

    @Test
    void aReadFailureClosesTheOldConnectionBeforeRecoveryUsesANewConnection() throws Exception {
        Fixture fixture = new Fixture(3);
        RedisConnection recovered = mock(RedisConnection.class);
        RedisStreamCommands recoveredStreams = mock(RedisStreamCommands.class);
        when(recovered.streamCommands()).thenReturn(recoveredStreams);
        AtomicBoolean oldClosed = new AtomicBoolean();
        AtomicInteger opens = new AtomicInteger();
        when(fixture.factory.getConnection()).thenAnswer(invocation -> {
            if (opens.incrementAndGet() == 1) return fixture.connection;
            assertThat(oldClosed).isTrue();
            return recovered;
        });
        doAnswer(invocation -> { oldClosed.set(true); return null; }).when(fixture.connection).close();
        when(fixture.streams.xRead(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenThrow(new RedisSystemException("synthetic read failure", new IllegalStateException("test")));
        String runId = "synthetic-recovery-reader";
        ByteRecord event = fixture.record(runId, 1, "synthetic recovery");
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        doAnswer(invocation -> reads.incrementAndGet() == 1 ? List.of(event) : interruptedRead(waiting, reader))
                .when(recoveredStreams).xRead(any(StreamReadOptions.class), any(StreamOffset.class));
        AtomicReference<RunEvent> delivered = new AtomicReference<>();
        AtomicInteger errors = new AtomicInteger();
        try (var bus = fixture.bus) {
            var subscription = bus.subscribe(runId, 0, delivered::set, ignored -> errors.incrementAndGet());
            assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(delivered.get().seq()).isEqualTo(1);
            assertThat(bus.status().readFailures()).isEqualTo(1);
            assertThat(errors).hasValue(0);
            verify(fixture.factory, times(2)).getConnection();
            verify(fixture.connection).close();
            verify(recovered, never()).close();
            subscription.close();
            awaitStopped(reader);
            verify(recovered).close();
            assertThat(bus.status().readFailures()).isEqualTo(1);
        }
    }

    @Test
    void connectionAcquisitionFailuresAreRetriedOnlyToTheLimitAndNotifyOnce() throws Exception {
        Fixture fixture = new Fixture(3);
        AtomicReference<Thread> reader = new AtomicReference<>();
        when(fixture.factory.getConnection()).thenAnswer(invocation -> {
            reader.set(Thread.currentThread());
            throw new RedisSystemException("synthetic connection failure", new IllegalStateException("test"));
        });
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try (var bus = fixture.bus) {
            bus.subscribe("synthetic-open-failure", 0, ignored -> {}, ignored -> {
                errors.incrementAndGet();
                failed.countDown();
            });
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
            awaitStopped(reader);
            assertThat(bus.status().readFailures()).isEqualTo(3);
            assertThat(bus.status().readerCount()).isZero();
            assertThat(errors).hasValue(1);
            verify(fixture.factory, times(3)).getConnection();
            verify(fixture.connection, never()).close();
        }
    }

    @Test
    void aConnectionReturnedAfterUnsubscriptionIsClosedWithoutStartingXread() throws Exception {
        Fixture fixture = new Fixture(3);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        when(fixture.factory.getConnection()).thenAnswer(invocation -> {
            reader.set(Thread.currentThread());
            entered.countDown();
            try {
                if (!new CountDownLatch(1).await(3, TimeUnit.SECONDS)) throw new AssertionError("Reader was not interrupted");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return fixture.connection;
        });
        AtomicInteger errors = new AtomicInteger();
        try (var bus = fixture.bus) {
            var subscription = bus.subscribe("synthetic-open-race", 0, ignored -> {}, ignored -> errors.incrementAndGet());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.close();
            awaitStopped(reader);
            verify(fixture.connection).close();
            verify(fixture.streams, never()).xRead(any(StreamReadOptions.class), any(StreamOffset.class));
            assertThat(bus.status().readFailures()).isZero();
            assertThat(errors).hasValue(0);
        }
    }

    @Test
    void stoppingTheBusReleasesTheBlockingConnectionAndNotifiesOnlyTheStop() throws Exception {
        Fixture fixture = new Fixture(3);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        doAnswer(invocation -> interruptedRead(entered, reader)).when(fixture.streams)
                .xRead(any(StreamReadOptions.class), any(StreamOffset.class));
        AtomicInteger errors = new AtomicInteger();
        try (var bus = fixture.bus) {
            bus.subscribe("synthetic-bus-stop", 0, ignored -> {}, ignored -> errors.incrementAndGet());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            bus.close();
            awaitStopped(reader);
            verify(fixture.connection).close();
            assertThat(bus.status().readFailures()).isZero();
            assertThat(bus.status().readerCount()).isZero();
            assertThat(bus.status().subscriptionCount()).isZero();
            assertThat(errors).hasValue(1);
        }
    }

    @Test
    void aFailedReleaseDuringActiveRecoveryIsCountedAndStopsFurtherReconnects() throws Exception {
        Fixture fixture = new Fixture(3);
        AtomicReference<Thread> reader = new AtomicReference<>();
        when(fixture.streams.xRead(any(StreamReadOptions.class), any(StreamOffset.class))).thenAnswer(invocation -> {
            reader.set(Thread.currentThread());
            throw new RedisSystemException("synthetic read failure", new IllegalStateException("test"));
        });
        RedisSystemException releaseFailure = new RedisSystemException("synthetic release failure", new IllegalStateException("test"));
        doThrow(releaseFailure).when(fixture.connection).close();
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        try (var bus = fixture.bus) {
            bus.subscribe("synthetic-release-failure", 0, ignored -> {}, failure -> {
                error.set(failure);
                errors.incrementAndGet();
                failed.countDown();
            });
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
            awaitStopped(reader);
            assertThat(bus.status().readFailures()).isEqualTo(2);
            assertThat(errors).hasValue(1);
            assertThat(error.get()).isInstanceOf(EventStreamReadException.class).hasCause(releaseFailure);
            verify(fixture.factory).getConnection();
            verify(fixture.connection).close();
        }
    }

    private static Object interruptedRead(CountDownLatch entered, AtomicReference<Thread> reader) {
        reader.set(Thread.currentThread());
        entered.countDown();
        try {
            if (!new CountDownLatch(1).await(3, TimeUnit.SECONDS)) throw new AssertionError("Reader was not interrupted");
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RedisSystemException("synthetic closed read", interrupted);
        }
    }

    private static void awaitStopped(AtomicReference<Thread> reader) throws InterruptedException {
        assertThat(reader.get()).isNotNull();
        reader.get().join(2000);
        assertThat(reader.get().isAlive()).isFalse();
    }

    private static List<?> emptySnapshot() {
        return List.of("0", "", List.of());
    }

    private static final class Fixture {
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        private final RedisConnection connection = mock(RedisConnection.class);
        private final RedisStreamCommands streams = mock(RedisStreamCommands.class);
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final RedisRunEventBus bus;

        private Fixture(int retryLimit) {
            when(redis.getConnectionFactory()).thenReturn(factory);
            when(redis.getStringSerializer()).thenReturn(StringRedisSerializer.UTF_8);
            when(factory.getConnection()).thenReturn(connection);
            when(connection.streamCommands()).thenReturn(streams);
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(emptySnapshot());
            bus = new RedisRunEventBus(redis, mapper,
                    new RedisEventProperties("redis", 2000, Duration.ofHours(24), Duration.ofMillis(100), 256, retryLimit, 2048));
        }

        private ByteRecord record(String runId, long seq, String text) throws Exception {
            RunEvent event = RunEvent.of(runId, RunEventType.DELTA, "llm", Map.of("text", text));
            return MapRecord.create(RedisRunEventBus.streamKey(runId), Map.of("event", mapper.writeValueAsString(event)))
                    .withId(RecordId.of(seq + "-0")).serialize(StringRedisSerializer.UTF_8);
        }
    }
}
