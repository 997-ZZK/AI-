package com.aiflow.engine.retrieval;

import java.util.Map;

/**
 * 一次检索请求。embedding 由调用方（{@code RagRetrievalService}）算好后传入：
 * 本地 provider 直接用它算余弦；云端知识库 provider 可以只用 text（服务端自己向量化）。
 */
public record RetrievalQuery(
        String text,
        float[] embedding,
        String kbId,
        int topK,
        double minScore,
        Map<String, String> filters
) {

    public RetrievalQuery {
        text = text == null ? "" : text;
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
