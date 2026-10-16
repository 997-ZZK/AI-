package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "aiflow.dispatch")
public record DispatchProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("true") boolean workerEnabled,
                                 @DefaultValue("16") int workers,
                                 @DefaultValue("200") long pollMs,
                                 @DefaultValue("30s") Duration lease,
                                 @DefaultValue("50") int recoveryBatchSize) {
    public DispatchProperties {
        if (workers < 1 || pollMs < 10 || lease == null || lease.toMillis() < 300 || recoveryBatchSize < 1) {
            throw new IllegalArgumentException("Invalid durable dispatch configuration");
        }
    }

    public static DispatchProperties disabled() {
        return new DispatchProperties(false, false, 16, 200, Duration.ofSeconds(30), 50);
    }
}
