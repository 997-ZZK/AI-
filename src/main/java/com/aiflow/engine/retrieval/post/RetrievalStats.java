package com.aiflow.engine.retrieval.post;

/** 后处理各阶段的切片数量，用于日志、SSE 与黄金集回归定位问题。 */
public record RetrievalStats(
        int raw,
        int afterThreshold,
        int afterTopK,
        int afterMmr,
        int afterMerge
) {
}
