package com.aiflow.engine.budget;

import com.aiflow.engine.config.BudgetProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static com.aiflow.engine.budget.TokenBudgetService.Status.EXHAUSTED;
import static com.aiflow.engine.budget.TokenBudgetService.Status.NORMAL;
import static com.aiflow.engine.budget.TokenBudgetService.Status.SOFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 额度账本的规则用例。
 *
 * <p>重点不是「能加数」，而是四个边界：软阈值在哪一分触发、硬上限在哪一分封顶、跨天怎么归零、
 * 关掉开关之后到底还动不动存储。这些都是「看起来能跑、事后算不清账」的高发区。
 */
class TokenBudgetServiceTest {

    /** 2026-09-20T02:00:00Z = 上海时间当天 10:00。 */
    private static final Instant NOW = Instant.parse("2026-09-20T02:00:00Z");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void accumulatesUsagePerUser() {
        TokenBudgetService service = service(100, 0.8, new TickingClock(NOW, SHANGHAI));

        service.charge(alice, 30);

        assertThat(service.snapshot(alice).used()).isEqualTo(30);
        assertThat(service.snapshot(bob).used()).as("额度是用户级：一个人用掉的不能算到别人头上").isZero();
    }

    @Test
    void turnsSoftAtTheRatioAndExhaustedAtTheLimit() {
        TokenBudgetService service = service(100, 0.8, new TickingClock(NOW, SHANGHAI));

        assertThat(service.charge(alice, 79).status()).isEqualTo(NORMAL);
        assertThat(service.charge(alice, 1).status()).as("80/100 刚好到软阈值").isEqualTo(SOFT);
        assertThat(service.charge(alice, 19).status()).isEqualTo(SOFT);
        assertThat(service.charge(alice, 1).status()).as("100/100 视为用尽").isEqualTo(EXHAUSTED);
    }

    @Test
    void keepsCountingAfterExhaustion() {
        TokenBudgetService service = service(100, 0.8, new TickingClock(NOW, SHANGHAI));

        service.charge(alice, 120);

        TokenBudgetService.Snapshot snapshot = service.snapshot(alice);
        assertThat(snapshot.used()).as("账本只负责算账，拦不拦是调用方的事").isEqualTo(120);
        assertThat(snapshot.ratio()).isEqualTo(1.0);
    }

    @Test
    void resetsWhenTheDayChanges() {
        TickingClock clock = new TickingClock(NOW, SHANGHAI);
        TokenBudgetService service = service(100, 0.8, clock);
        service.charge(alice, 90);

        clock.tick(Duration.ofHours(15));

        TokenBudgetService.Snapshot nextDay = service.snapshot(alice);
        assertThat(nextDay.used()).as("跨天靠 key 上的日期 + TTL 归零，不靠定时任务").isZero();
        assertThat(nextDay.status()).isEqualTo(NORMAL);
        assertThat(nextDay.resetAt()).isEqualTo(Instant.parse("2026-09-21T16:00:00Z"));
    }

    @Test
    void unlimitedAndDisabledNeverBlockAndNeverCount() {
        assertThat(service(-1, 0.8, new TickingClock(NOW, SHANGHAI)).charge(alice, 1_000_000).status())
                .as("limit <= 0 表示不限额度").isEqualTo(NORMAL);
        assertThat(service(-1, 0.8, new TickingClock(NOW, SHANGHAI)).snapshot(alice).limit()).isEqualTo(-1);

        TokenBudgetService disabled = new TokenBudgetService(new InMemoryTokenUsageStore(),
                new BudgetProperties(false, 10, 0.8, "Asia/Shanghai", "额度已用尽", "memory"),
                new TickingClock(NOW, SHANGHAI));
        disabled.charge(alice, 9_999);
        assertThat(disabled.snapshot(alice).used()).as("关掉就是关掉：连计数都不落").isZero();
    }

    @Test
    void reportsStoreKind() {
        assertThat(service(100, 0.8, new TickingClock(NOW, SHANGHAI)).storeKind()).isEqualTo("memory");
    }

    @Test
    void rejectsAnInvalidResetZoneAtStartup() {
        assertThatThrownBy(() -> new TokenBudgetService(new InMemoryTokenUsageStore(),
                new BudgetProperties(true, 100, 0.8, "Mars/Olympus", "额度已用尽", "memory")))
                .as("时区写错会让额度在错误的时刻重置，必须让人知道，而不是悄悄按 UTC 算")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("时区非法");
    }

    private static TokenBudgetService service(long limit, double softRatio, Clock clock) {
        return service(limit, softRatio, clock, "Asia/Shanghai");
    }

    private static TokenBudgetService service(long limit, double softRatio, Clock clock, String zone) {
        return new TokenBudgetService(new InMemoryTokenUsageStore(),
                new BudgetProperties(true, limit, softRatio, zone, "额度已用尽", "memory"), clock);
    }

    /** 可手动拨动的时钟：跨天这种用例用真时间等一天显然不行。 */
    private static final class TickingClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private TickingClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void tick(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new TickingClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}