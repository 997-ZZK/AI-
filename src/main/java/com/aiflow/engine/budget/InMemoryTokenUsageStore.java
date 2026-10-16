package com.aiflow.engine.budget;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机内存计数：默认实现，不依赖任何外部服务。
 *
 * <p>口径要说清楚：它统计的是**本进程**的用量。单实例部署完全准确；多实例部署下每个实例各算一份，
 * 用户实际可用额度会被放大到「实例数 × 额度」。要跨实例共享请切 {@code aiflow.budget.store=redis}。
 */
@Component
@ConditionalOnProperty(name = "aiflow.budget.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryTokenUsageStore implements TokenUsageStore {

    private final Map<String, Entry> counters = new ConcurrentHashMap<>();

    @Override
    public long addAndGet(String key, long delta, Duration ttl) {
        Entry entry = counters.compute(key, (ignored, current) -> {
            Instant now = Instant.now();
            long base = current == null || current.expiredAt(now) ? 0 : current.value();
            return new Entry(base + delta, now.plus(ttl));
        });
        return entry.value();
    }

    @Override
    public long get(String key) {
        Entry entry = counters.get(key);
        if (entry == null) {
            return 0;
        }
        if (entry.expiredAt(Instant.now())) {
            counters.remove(key, entry);
            return 0;
        }
        return entry.value();
    }

    @Override
    public String kind() {
        return "memory";
    }

    private record Entry(long value, Instant expiresAt) {

        boolean expiredAt(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }
}