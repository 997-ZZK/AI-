package com.aiflow.engine.session;

import com.aiflow.engine.config.EngineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSessionLockTest {

    @Test
    void renewsAtOneThirdTtlAndStopsAfterClose() {
        Fixture fixture = new Fixture();
        try (RedisSessionLock lock = fixture.lock()) {
            SessionLock.Handle handle = lock.tryAcquire("session:one").orElseThrow();
            assertThat(handle.isValid()).isTrue();
            verify(fixture.scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(10_000L),
                    eq(10_000L), eq(TimeUnit.MILLISECONDS));
            fixture.renewal.get().run();
            assertThat(fixture.renewals).hasValue(1);
            handle.close();
            fixture.renewal.get().run();
            assertThat(fixture.renewals).hasValue(1);
            assertThat(handle.isValid()).isFalse();
            assertThat(fixture.owner.get()).isNull();
            verify(fixture.scheduled, atLeastOnce()).cancel(false);
        }
        verify(fixture.scheduler).shutdownNow();
    }

    @Test
    void tokenMismatchNeverRenewsOrDeletesNewOwnersLock() {
        Fixture fixture = new Fixture();
        try (RedisSessionLock lock = fixture.lock()) {
            SessionLock.Handle handle = lock.tryAcquire("session:one").orElseThrow();
            fixture.owner.set("other-owner");
            fixture.renewal.get().run();
            assertThat(handle.isValid()).isFalse();
            assertThat(fixture.renewals).hasValue(0);
            handle.close();
            assertThat(fixture.owner).hasValue("other-owner");
        }
    }

    @Test
    void redisFailurePermanentlyInvalidatesCurrentHandle() {
        Fixture fixture = new Fixture();
        try (RedisSessionLock lock = fixture.lock()) {
            SessionLock.Handle handle = lock.tryAcquire("session:one").orElseThrow();
            fixture.unavailable.set(true);
            assertThat(handle.isValid()).isFalse();
            fixture.unavailable.set(false);
            fixture.renewal.get().run();
            assertThat(handle.isValid()).isFalse();
            assertThat(fixture.renewals).hasValue(0);
        }
    }

    @Test
    void occupiedLockDoesNotStartWatchdogAndLambdaHandleRemainsCompatible() {
        Fixture fixture = new Fixture();
        fixture.owner.set("occupied");
        try (RedisSessionLock lock = fixture.lock()) {
            assertThat(lock.tryAcquire("session:one")).isEmpty();
            assertThat(fixture.renewal.get()).isNull();
        }
        SessionLock.Handle legacy = () -> {};
        assertThat(legacy.isValid()).isTrue();
    }

    private static final class Fixture {
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        private final ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        private final AtomicReference<String> owner = new AtomicReference<>();
        private final AtomicReference<Runnable> renewal = new AtomicReference<>();
        private final AtomicInteger renewals = new AtomicInteger();
        private final AtomicBoolean unavailable = new AtomicBoolean();

        @SuppressWarnings("unchecked")
        private Fixture() {
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenAnswer(invocation -> owner.compareAndSet(null, invocation.getArgument(1)));
            doAnswer(invocation -> {
                renewal.set(invocation.getArgument(0));
                return scheduled;
            }).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
                if (unavailable.get()) {
                    throw new IllegalStateException("test Redis unavailable");
                }
                RedisScript<Long> script = invocation.getArgument(0);
                Object[] args = (Object[]) invocation.getRawArguments()[2];
                boolean owns = java.util.Objects.equals(owner.get(), args[0]);
                if (script.getScriptAsString().contains("pttl")) {
                    return owns ? 30_000L : -1L;
                }
                if (script.getScriptAsString().contains("pexpire")) {
                    if (owns) {
                        renewals.incrementAndGet();
                    }
                    return owns ? 1L : 0L;
                }
                if (owns) {
                    owner.set(null);
                }
                return owns ? 1L : 0L;
            });
        }

        private RedisSessionLock lock() {
            return new RedisSessionLock(redis, EngineProperties.defaults(), scheduler);
        }
    }
}
