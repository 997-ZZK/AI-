package com.aiflow.engine.memory.longterm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 抽取节流：三条阈值同时满足才抽，且失败也推进记账（少抽一次不花钱，多抽一次是钱）。 */
class LongMemoryThrottleTest {

    private static final LongMemoryProperties PROPERTIES =
            new LongMemoryProperties(true, true, true, 8, 6, Duration.ofMinutes(3), 5, 0.6, 200, 200, 5, -1, 800, 200);

    private final LongMemoryThrottle throttle = new LongMemoryThrottle();
    private final UUID sessionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-23T10:00:00Z");

    @Test
    void waitsUntilTheSessionHasEnoughMessages() {
        assertThat(throttle.start(sessionId, 4, now, PROPERTIES))
                .isEqualTo(new LongMemoryThrottle.Decision(false, "not-enough-messages"));
    }

    @Test
    void firstEligibleRunExtractsThenWaitsForFreshMessages() {
        assertThat(throttle.start(sessionId, 8, now, PROPERTIES).extract()).isTrue();
        throttle.finish(sessionId);

        assertThat(throttle.start(sessionId, 12, now.plusSeconds(600), PROPERTIES))
                .as("距上次只新增 4 条（阈值 6）").isEqualTo(new LongMemoryThrottle.Decision(false, "not-enough-new-messages"));

        assertThat(throttle.start(sessionId, 14, now.plusSeconds(10), PROPERTIES))
                .as("新增够了但间隔没到").isEqualTo(new LongMemoryThrottle.Decision(false, "too-soon"));

        assertThat(throttle.start(sessionId, 14, now.plus(Duration.ofMinutes(5)), PROPERTIES).extract())
                .as("新增 6 条且间隔 5 分钟 -> 可以抽").isTrue();
    }

    @Test
    void aFailedExtractionStillAdvancesTheBookkeeping() {
        throttle.start(sessionId, 20, now, PROPERTIES);
        throttle.finish(sessionId);

        assertThat(throttle.start(sessionId, 20, now.plusSeconds(3600), PROPERTIES).extract())
                .as("上一次失败了也不该每轮重试：要再攒够新增消息").isFalse();
    }

    @Test
    void concurrentRunsDoNotBothExtract() {
        throttle.start(sessionId, 20, now, PROPERTIES);

        assertThat(throttle.start(sessionId, 40, now.plusSeconds(600), PROPERTIES))
                .isEqualTo(new LongMemoryThrottle.Decision(false, "in-flight"));
    }

    @Test
    void trackedSessionsStayBounded() {
        for (int index = 0; index < LongMemoryThrottle.MAX_TRACKED_SESSIONS + 50; index++) {
            throttle.start(UUID.randomUUID(), 8, now, PROPERTIES);
        }
        assertThat(throttle.trackedSessions()).isEqualTo(LongMemoryThrottle.MAX_TRACKED_SESSIONS);
    }
}
