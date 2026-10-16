package com.aiflow.engine.budget;

import com.aiflow.engine.config.BudgetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.UUID;

/**
 * 每用户每日 token 额度：查询、扣减、判定。
 *
 * <p>状态机只有三档，语义要写死，否则「为什么这次走了本地」会变成玄学：
 * <ul>
 *   <li>{@code NORMAL}：正常，按配置的分级顺序走。</li>
 *   <li>{@code SOFT}：用量达到软阈值，本用户的新请求优先便宜档位（降级但可用）。</li>
 *   <li>{@code EXHAUSTED}：用量达到硬上限，直接给预设话术，不再发起任何模型调用。</li>
 * </ul>
 *
 * <p>计数键按「自然日 + 用户」切分：{@code aiflow:budget:<yyyy-MM-dd>:<userId>}，TTL 到次日零点再多留一小时。
 * 跨天重置靠 TTL，不靠定时任务。
 */
@Service
public class TokenBudgetService {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetService.class);
    private static final String KEY_PREFIX = "aiflow:budget:";
    /** 跨天时给 TTL 留的余量：避免零点前后同一秒的读写在两个 key 上打架。 */
    private static final Duration TTL_MARGIN = Duration.ofHours(1);

    public enum Status {
        NORMAL, SOFT, EXHAUSTED
    }

    public record Snapshot(long used, long limit, double ratio, Status status, Instant resetAt, String store) {

        public boolean soft() {
            return status == Status.SOFT;
        }

        public boolean exhausted() {
            return status == Status.EXHAUSTED;
        }

        public boolean enforced() {
            return limit > 0;
        }
    }

    private final TokenUsageStore store;
    private final BudgetProperties properties;
    private final Clock clock;

    @Autowired
    public TokenBudgetService(TokenUsageStore store, BudgetProperties properties) {
        this(store, properties, Clock.system(resolveZone(properties.resetZone())));
    }

    TokenBudgetService(TokenUsageStore store, BudgetProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public Snapshot snapshot(UUID userId) {
        if (!properties.enforced()) {
            return unlimitedSnapshot();
        }
        return build(store.get(key(userId)));
    }

    /** 扣减一次用量并返回最新状态；未启用或不限额度时不做任何存储访问。 */
    public Snapshot charge(UUID userId, long tokens) {
        if (!properties.enforced() || tokens <= 0) {
            return snapshot(userId);
        }
        long used = store.addAndGet(key(userId), tokens, ttl());
        Snapshot snapshot = build(used);
        if (snapshot.exhausted()) {
            log.info("用户 {} 今日额度已用尽: {}/{} token（store={}）", userId, used, snapshot.limit(), store.kind());
        }
        return snapshot;
    }

    /** 状态接口展示用：当前计数口径（memory = 单机；redis = 多实例共享）。 */
    public String storeKind() {
        return store.kind();
    }

    private Snapshot build(long used) {
        long limit = properties.perUserDailyTokens();
        double ratio = (double) used / limit;
        Status status = used >= limit ? Status.EXHAUSTED
                : used >= Math.ceil(limit * properties.softThresholdRatio()) ? Status.SOFT : Status.NORMAL;
        return new Snapshot(used, limit, Math.min(1.0, ratio), status, resetAt(), store.kind());
    }

    private Snapshot unlimitedSnapshot() {
        return new Snapshot(0, -1, 0, Status.NORMAL, resetAt(), store.kind());
    }

    private String key(UUID userId) {
        return KEY_PREFIX + LocalDate.now(clock) + ":" + userId;
    }

    private Duration ttl() {
        return Duration.between(clock.instant(), resetAt()).plus(TTL_MARGIN);
    }

    private Instant resetAt() {
        return LocalDate.now(clock).plusDays(1).atStartOfDay(clock.getZone()).toInstant();
    }

    private static ZoneId resolveZone(String zone) {
        try {
            return ZoneId.of(zone == null || zone.isBlank() ? "Asia/Shanghai" : zone);
        } catch (ZoneRulesException e) {
            // 时区写错会让额度在错误的时刻重置，属于「必须让人知道」的配置错误 —— 直接让服务起不来
            throw new IllegalStateException("额度重置时区非法: " + zone, e);
        }
    }
}