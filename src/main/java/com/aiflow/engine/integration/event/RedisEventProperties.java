package com.aiflow.engine.integration.event;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "aiflow.events")
public record RedisEventProperties(
        @DefaultValue("memory") String mode,
        @DefaultValue("2000") int maxEntries,
        @DefaultValue("24h") Duration retention,
        @DefaultValue("1s") Duration readBlock,
        @DefaultValue("256") int readBatchSize,
        @DefaultValue("3") int readRetryLimit,
        @DefaultValue("2048") int sseQueueCapacity) {
    public RedisEventProperties {
        if (!"memory".equals(mode) && !"redis".equals(mode)) {
            throw new IllegalArgumentException("aiflow.events.mode must be memory or redis");
        }
        if (maxEntries < 1 || maxEntries > 2000 || retention == null || retention.isZero()
                || retention.isNegative() || retention.compareTo(Duration.ofHours(24)) > 0
                || readBlock == null || readBlock.toMillis() < 1 || readBlock.compareTo(Duration.ofSeconds(10)) > 0
                || readBatchSize < 1 || readBatchSize > 2000 || readRetryLimit < 1 || sseQueueCapacity < 1) {
            throw new IllegalArgumentException("invalid aiflow.events retention/reader/queue limits");
        }
    }

    public static RedisEventProperties defaults() {
        return new RedisEventProperties("memory", 2000, Duration.ofHours(24), Duration.ofSeconds(1), 256, 3, 2048);
    }
}
