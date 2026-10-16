package com.aiflow.engine.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 短期记忆：会话窗口 + 滚动摘要（设计见 docs/memory-design.md）。
 *
 * <p>两个开关刻意分开：**窗口注入默认开**（只读库、不花模型调用，是「能听懂上一句」的最低保障），
 * **滚动摘要默认关**（要调模型、要花钱，与 OCR 同一取舍，打开方式写进 README）。
 *
 * <p>越界值在这里夹到合法区间，理由与 {@code BudgetProperties} 相同：一个永远不触发或永远触发的阈值
 * 是「配置错了却没人发现」的典型。
 */
@ConfigurationProperties(prefix = "aiflow.memory")
public record MemoryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("8") int windowSize,
        @DefaultValue("4000") int windowMaxChars,
        @DefaultValue("false") boolean summaryEnabled,
        @DefaultValue("6") int summaryChunkSize,
        @DefaultValue("8000") int summaryMaxInputChars,
        @DefaultValue("1200") int summaryMaxChars,
        @DefaultValue("true") boolean summaryPreferCheapTiers,
        @DefaultValue("5") int summaryMaxAttempts
) {

    public MemoryProperties {
        windowSize = Math.max(0, windowSize);
        windowMaxChars = Math.max(0, windowMaxChars);
        summaryChunkSize = Math.max(1, summaryChunkSize);
        summaryMaxInputChars = Math.max(1, summaryMaxInputChars);
        summaryMaxChars = Math.max(1, summaryMaxChars);
        summaryMaxAttempts = Math.max(1, summaryMaxAttempts);
    }

    public static MemoryProperties defaults() {
        return new MemoryProperties(true, 8, 4000, false, 6, 8000, 1200, true, 5);
    }

    /** 摘要关着（或整体关着）时会话摘要不必读表。 */
    public boolean summaryOff() {
        return !enabled || !summaryEnabled;
    }
}
