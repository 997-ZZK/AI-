package com.aiflow.engine.integration.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(RedisEventProperties.class)
public class RedisEventConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "aiflow.events.mode", havingValue = "redis")
    public RedisRunEventBus redisRunEventBus(StringRedisTemplate redis, ObjectMapper mapper,
                                           RedisEventProperties properties) {
        return new RedisRunEventBus(redis, mapper, properties);
    }
}
