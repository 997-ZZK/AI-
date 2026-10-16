package com.aiflow.engine.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多实例共享计数：Redis INCRBY + EXPIRE。
 *
 * <p>唯一值得强调的设计点：**Redis 挂了不能把聊天带崩**。额度是成本控制，不是安全边界 ——
 * 计数不可用时宁可少算一点、继续服务，也不该让整条对话链路 500。所以这里在写失败时退化成
 * 进程内计数（{@link InMemoryTokenUsageStore}），并打一条带节流的 WARN：降级状态要能被看见，
 * 但不能变成每请求一行的日志洪水。
 */
@Component
@ConditionalOnProperty(name = "aiflow.budget.store", havingValue = "redis")
public class RedisTokenUsageStore implements TokenUsageStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenUsageStore.class);
    private static final long WARN_INTERVAL_MILLIS = 60_000;

    private final StringRedisTemplate redisTemplate;
    private final InMemoryTokenUsageStore fallback = new InMemoryTokenUsageStore();
    private final AtomicLong lastWarnAt = new AtomicLong();

    public RedisTokenUsageStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public long addAndGet(String key, long delta, Duration ttl) {
        try {
            Long value = redisTemplate.opsForValue().increment(key, delta);
            redisTemplate.expire(key, ttl);
            return value == null ? delta : value;
        } catch (DataAccessException e) {
            warnDegraded(e);
            return fallback.addAndGet(key, delta, ttl);
        }
    }

    @Override
    public long get(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("额度计数值不是数字，按 0 处理: key={}", key);
            return 0;
        } catch (DataAccessException e) {
            warnDegraded(e);
            return fallback.get(key);
        }
    }

    @Override
    public String kind() {
        return "redis";
    }

    private void warnDegraded(DataAccessException e) {
        long now = Instant.now().toEpochMilli();
        long previous = lastWarnAt.get();
        if (now - previous >= WARN_INTERVAL_MILLIS && lastWarnAt.compareAndSet(previous, now)) {
            log.warn("额度计数 Redis 不可用，已退化为进程内计数（多实例下额度会被放大）: {}", e.toString());
        }
    }
}