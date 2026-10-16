package com.aiflow.engine.memory.longterm;

/**
 * 抽取出来的一条候选记忆（还没落库）。
 *
 * <p>{@code memKey} 是归一化后的去重键（见 {@link MemoryKeys#normalize}），{@code displayKey} 是给人看的原文；
 * {@code supersedes} 由模型判定「这是对已有条目的纠正，还是一件新事」—— 覆盖与否不看「谁更新」，也不看
 * 「谁置信度高」，理由见 docs/long-memory-design.md §3.3。
 */
public record MemoryCandidate(MemoryKind kind, String displayKey, String memKey, String value, double confidence,
                              String evidence, boolean supersedes) {
}
