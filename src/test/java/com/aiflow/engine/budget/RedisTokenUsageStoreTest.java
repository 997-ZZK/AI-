package com.aiflow.engine.budget;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 计数的降级用例：把连接指向一个必然连不上的端口，模拟「Redis 挂了」。
 *
 * <p>这条用例存在的意义：额度是成本控制，不是安全边界。计数不可用时宁可少算一点、继续服务，
 * 也不该让每一次对话都因为一个附属组件而失败。断言的就是这件事，而不是 Redis 本身好不好使。
 */
class RedisTokenUsageStoreTest {

    @Test
    void degradesToLocalCountingWhenRedisIsUnreachable() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 1);
        factory.afterPropertiesSet();
        try {
            RedisTokenUsageStore store = new RedisTokenUsageStore(new StringRedisTemplate(factory));

            assertThat(store.addAndGet("aiflow:budget:test:1", 5, Duration.ofMinutes(5))).isEqualTo(5);
            assertThat(store.addAndGet("aiflow:budget:test:1", 7, Duration.ofMinutes(5)))
                    .as("写失败后退化为进程内计数，后续请求照样能算")
                    .isEqualTo(12);
            assertThat(store.get("aiflow:budget:test:1")).isEqualTo(12);
            assertThat(store.kind()).as("口径要如实展示：它配置的是 redis，只是当前降级了").isEqualTo("redis");
        } finally {
            factory.destroy();
        }
    }
}