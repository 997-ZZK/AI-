package com.aiflow.engine.retrieval.post;

import com.aiflow.engine.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;

/** 绝对阈值过滤：归一化分数低于 minScore 的切片直接丢弃。 */
public final class ThresholdFilter {

    private ThresholdFilter() {
    }

    public static List<RetrievedChunk> apply(List<RetrievedChunk> chunks, double minScore) {
        List<RetrievedChunk> kept = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            if (chunk.score() >= minScore) {
                kept.add(chunk);
            }
        }
        return List.copyOf(kept);
    }
}
