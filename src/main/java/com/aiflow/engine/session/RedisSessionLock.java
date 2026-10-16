package com.aiflow.engine.session;

import com.aiflow.engine.config.EngineProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Component
@ConditionalOnProperty(name = "aiflow.engine.session.redis-lock-enabled", havingValue = "true")
public class RedisSessionLock implements SessionLock, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionLock.class);
    private static final String KEY_PREFIX = "aiflow:lock:";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> VALID_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pttl', KEYS[1]) else return -1 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final EngineProperties properties;
    private final ScheduledExecutorService watchdog;
    private final Set<RedisHandle> handles = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Autowired
    public RedisSessionLock(StringRedisTemplate redisTemplate, EngineProperties properties) {
        this(redisTemplate, properties, Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("session-lock-watchdog").factory()));
    }

    RedisSessionLock(StringRedisTemplate redisTemplate, EngineProperties properties,
                     ScheduledExecutorService watchdog) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.watchdog = watchdog;
    }

    @Override
    public Optional<Handle> tryAcquire(String key) {
        if (closed.get()) {
            throw new IllegalStateException("会话锁已关闭");
        }
        long ttlMs = properties.session().lockTtlMs();
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("会话锁 TTL 必须大于 0");
        }
        String redisKey = KEY_PREFIX + key;
        String token = UUID.randomUUID().toString();
        long acquiredAt = System.nanoTime();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, token, Duration.ofMillis(ttlMs));
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        RedisHandle handle = new RedisHandle(redisKey, token, ttlMs, acquiredAt);
        handles.add(handle);
        try {
            long interval = Math.max(1, ttlMs / 3);
            handle.renewal = watchdog.scheduleWithFixedDelay(handle::renew, interval, interval, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            handle.close();
            throw e;
        }
        return Optional.of(handle);
    }

    @Override
    @PreDestroy
    public void close() {
        if (closed.compareAndSet(false, true)) {
            watchdog.shutdownNow();
            handles.forEach(RedisHandle::close);
        }
    }

    private final class RedisHandle implements Handle {

        private final String key;
        private final String token;
        private final long ttlMs;
        private final ReentrantLock operationLock = new ReentrantLock();
        private volatile long validUntil;
        private volatile boolean lost;
        private volatile boolean released;
        private volatile ScheduledFuture<?> renewal;

        private RedisHandle(String key, String token, long ttlMs, long acquiredAt) {
            this.key = key;
            this.token = token;
            this.ttlMs = ttlMs;
            validUntil = acquiredAt + TimeUnit.MILLISECONDS.toNanos(ttlMs);
        }

        @Override
        public boolean isValid() {
            operationLock.lock();
            try {
                if (!locallyValid()) {
                    return false;
                }
                Long remaining = redisTemplate.execute(VALID_SCRIPT, List.of(key), token);
                if (remaining == null || remaining <= 0 || !locallyValid()) {
                    lose();
                    return false;
                }
                return true;
            } catch (RuntimeException e) {
                lose();
                log.warn("会话锁有效性检查失败，停止当前执行（{}）", e.getClass().getSimpleName());
                return false;
            } finally {
                operationLock.unlock();
            }
        }

        private boolean locallyValid() {
            if (released || lost || System.nanoTime() >= validUntil) {
                lose();
                return false;
            }
            return true;
        }

        private void renew() {
            operationLock.lock();
            try {
                if (!locallyValid()) {
                    return;
                }
                long startedAt = System.nanoTime();
                Long renewed = redisTemplate.execute(RENEW_SCRIPT, List.of(key), token, String.valueOf(ttlMs));
                if (!Long.valueOf(1).equals(renewed)
                        || System.nanoTime() - startedAt >= TimeUnit.MILLISECONDS.toNanos(ttlMs)) {
                    lose();
                    return;
                }
                validUntil = startedAt + TimeUnit.MILLISECONDS.toNanos(ttlMs);
            } catch (RuntimeException e) {
                lose();
                log.warn("会话锁续租失败，停止当前执行（{}）", e.getClass().getSimpleName());
            } finally {
                operationLock.unlock();
            }
        }

        private void lose() {
            lost = true;
            if (renewal != null) {
                renewal.cancel(false);
            }
        }

        @Override
        public void close() {
            operationLock.lock();
            try {
                if (released) {
                    return;
                }
                released = true;
                lose();
                handles.remove(this);
                try {
                    redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
                } catch (RuntimeException e) {
                    log.warn("会话锁释放失败，等待 TTL 到期（{}）", e.getClass().getSimpleName());
                }
            } finally {
                operationLock.unlock();
            }
        }
    }
}
