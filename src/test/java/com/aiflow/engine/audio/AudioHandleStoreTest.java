package com.aiflow.engine.audio;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioHandleStoreTest {

    @Test
    void handleIsBoundToOpenSegmentAndDefensivelyCopiesBytes() {
        MutableClock clock = new MutableClock();
        AudioHandleStore store = new AudioHandleStore(clock, Duration.ofMinutes(5), 64, 100);
        UUID runId = UUID.randomUUID();
        store.begin(runId);
        byte[] bytes = {1, 2, 3};
        String handle = store.put(runId, new AudioInput(bytes, "audio/wav"));
        bytes[0] = 9;

        assertThat(store.get(runId, handle).bytes()).containsExactly(1, 2, 3);
        store.end(runId);
        assertThatThrownBy(() -> store.get(runId, handle))
                .isInstanceOfSatisfying(AudioHandleException.class,
                        e -> assertThat(e.reason()).isEqualTo(AudioHandleException.Reason.RUN_NOT_OPEN));
    }

    @Test
    void expiredHandleAndLateOldSegmentCannotWriteAfterSameRunReopens() {
        MutableClock clock = new MutableClock();
        AudioHandleStore store = new AudioHandleStore(clock, Duration.ofSeconds(5), 64, 100);
        UUID runId = UUID.randomUUID();
        store.begin(runId);
        AudioHandleStore.Access old = store.access(runId, () -> { });
        String handle = store.put(old, new AudioInput(new byte[]{1}, "audio/wav"), () -> { });
        clock.advance(Duration.ofSeconds(6));
        assertThatThrownBy(() -> store.get(old, handle, () -> { }))
                .isInstanceOfSatisfying(AudioHandleException.class,
                        e -> assertThat(e.reason()).isEqualTo(AudioHandleException.Reason.HANDLE_EXPIRED));
        store.end(runId);
        store.begin(runId);
        AudioHandleStore.Access current = store.access(runId, () -> { });
        assertThatThrownBy(() -> store.put(old, new AudioInput(new byte[]{2}, "audio/wav"), () -> { }))
                .isInstanceOf(AudioHandleException.class);
        assertThat(store.put(current, new AudioInput(new byte[]{3}, "audio/wav"), () -> { })).isNotBlank();
    }

    @Test
    void capacityIsBoundedWithoutEvictingAnotherRun() {
        AudioHandleStore store = new AudioHandleStore(Clock.systemUTC(), Duration.ofMinutes(5), 1, 3);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.begin(first);
        store.begin(second);
        String kept = store.put(first, new AudioInput(new byte[]{1, 2}, "audio/wav"));
        assertThatThrownBy(() -> store.put(second, new AudioInput(new byte[]{3, 4}, "audio/wav")))
                .isInstanceOfSatisfying(AudioHandleException.class,
                        e -> assertThat(e.reason()).isEqualTo(AudioHandleException.Reason.CAPACITY));
        assertThat(store.get(first, kept).bytes()).containsExactly(1, 2);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }
}
