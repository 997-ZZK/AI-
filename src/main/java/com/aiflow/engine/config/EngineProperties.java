package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "aiflow.engine")
public record EngineProperties(
        @DefaultValue("50") int maxSteps,
        @DefaultValue("60") int defaultNodeTimeoutSeconds,
        @DefaultValue("2") int defaultMaxAttempts,
        @DefaultValue("200") long defaultBackoffMillis,
        @DefaultValue("1000") int sessionQueueCapacity,
        @DefaultValue("1800000") long sseTimeoutMs,
        @DefaultValue("15000") long sseHeartbeatMs,
        @DefaultValue("basic-chat") String defaultWorkflow,
        @DefaultValue({"basic-chat", "rag-chat", "vision-chat", "approval-chat"}) String[] demoWorkflows,
        @DefaultValue("抱歉，任务多次尝试仍未成功，请稍后再试或换个问法。") String fallbackReply,
        @DefaultValue("2000") int eventBufferSize,
        @DefaultValue Session session
) {

    public record Session(
            @DefaultValue("false") boolean redisLockEnabled,
            @DefaultValue("30000") long lockTtlMs
    ) {
    }

    public static EngineProperties defaults() {
        return new EngineProperties(50, 60, 2, 200, 1000, 1_800_000, 15_000,
                "basic-chat", new String[]{"basic-chat", "rag-chat", "vision-chat", "approval-chat"},
                "抱歉，任务多次尝试仍未成功，请稍后再试或换个问法。", 2000,
                new Session(false, 30_000));
    }
}
