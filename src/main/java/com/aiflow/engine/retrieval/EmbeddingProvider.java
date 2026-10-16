package com.aiflow.engine.retrieval;

import java.util.List;

/**
 * 文本向量化 provider。维度由实现声明，调用方负责校验（{@link Vectors#requireDimensions}）。
 */
public interface EmbeddingProvider {

    String id();

    int dimensions();

    default boolean healthy() {
        return true;
    }

    List<float[]> embedAll(List<String> texts);

    default float[] embed(String text) {
        List<float[]> vectors = embedAll(List.of(text));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("embedding provider " + id() + " 未返回向量");
        }
        return vectors.get(0);
    }
}
