package com.aiflow.engine.memory.longterm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 长期记忆：事实/偏好抽取 + 向量召回（设计见 docs/long-memory-design.md §3.1）。
 *
 * <p>默认值取舍与 M4-1 摘要、OCR 同一口径：**只读库的默认开，要花模型调用的默认关**。
 * 于是 {@code recall-enabled=true}（召回只多一次 embedding 调用，是「记忆有没有用」的唯一出口），
 * 而 {@code extract-enabled=false}（抽取要一次对话调用 + 逐条 embedding，是花钱的那一端）。
 *
 * <p>越界值在这里夹到合法区间（与 {@code MemoryProperties}/{@code BudgetProperties} 同规矩）：
 * 一个永远不触发或永远触发的阈值，是「配置错了却没人发现」的典型。
 */
@ConfigurationProperties(prefix = "aiflow.long-memory")
public record LongMemoryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean recallEnabled,
        @DefaultValue("false") boolean extractEnabled,
        @DefaultValue("8") int extractMinMessages,
        @DefaultValue("6") int extractEveryMessages,
        @DefaultValue("3m") Duration extractMinInterval,
        @DefaultValue("5") int extractMaxCandidates,
        @DefaultValue("0.6") double extractMinConfidence,
        @DefaultValue("200") int extractMaxChars,
        @DefaultValue("200") int maxEntriesPerUser,
        @DefaultValue("5") int recallTopK,
        /** 空（{@code < 0}）= 继承 {@code aiflow.retrieval.embedding.min-score}，再退回 {@code aiflow.retrieval.min-score}。 */
        @DefaultValue("-1") double recallMinScore,
        @DefaultValue("800") int recallMaxChars,
        @DefaultValue("200") int recallQueryChars
) {

    public LongMemoryProperties {
        extractMinMessages = Math.max(0, extractMinMessages);
        extractEveryMessages = Math.max(1, extractEveryMessages);
        extractMinInterval = extractMinInterval == null || extractMinInterval.isNegative()
                ? Duration.ofMinutes(3) : extractMinInterval;
        extractMaxCandidates = Math.max(1, extractMaxCandidates);
        extractMinConfidence = Math.min(1.0, Math.max(0.0, extractMinConfidence));
        extractMaxChars = Math.max(1, extractMaxChars);
        maxEntriesPerUser = Math.max(1, maxEntriesPerUser);
        recallTopK = Math.max(1, recallTopK);
        recallMaxChars = Math.max(0, recallMaxChars);
        recallQueryChars = Math.max(0, recallQueryChars);
    }

    public static LongMemoryProperties defaults() {
        return new LongMemoryProperties(true, true, false, 8, 6, Duration.ofMinutes(3), 5, 0.6, 200, 200, 5, -1,
                800, 200);
    }

    /** 召回关着（或整体关着）时，装配路径不必碰长期记忆表。 */
    public boolean recallOff() {
        return !enabled || !recallEnabled;
    }

    /** 抽取关着（或整体关着）时，运行收口不必提交旁路任务。 */
    public boolean extractOff() {
        return !enabled || !extractEnabled;
    }
}
