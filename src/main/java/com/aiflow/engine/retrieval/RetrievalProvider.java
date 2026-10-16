package com.aiflow.engine.retrieval;

import java.util.List;

/**
 * 检索来源统一接口：本地向量库与云端知识库实现同一个契约，分数由 {@link ScoreNormalizer} 归一化后再统一比较。
 */
public interface RetrievalProvider {

    /** 来源标识：local / cloud 等，用于分数归一化与日志。 */
    String source();

    default boolean healthy() {
        return true;
    }

    List<RetrievedChunk> retrieve(RetrievalQuery query);
}
