package com.aiflow.engine.retrieval;

import com.aiflow.engine.common.Hashes;

/** 测试用切片构造器：字段一次填好，避免每个用例重复堆参数。 */
public final class TestChunks {

    private TestChunks() {
    }

    public static RetrievedChunk chunk(String docId, int chunkIndex, String text, double score) {
        return chunk("local", docId, chunkIndex, text, score, null);
    }

    public static RetrievedChunk chunk(String source, String docId, int chunkIndex, String text, double score,
                                       float[] embedding) {
        return new RetrievedChunk(source, docId, docId + "标题", docId + "-c" + chunkIndex, chunkIndex, "章节" + chunkIndex,
                null, null, text, Hashes.sha256Hex(text), score, score, embedding);
    }

    public static float[] oneHot(int dimensions, int hotIndex) {
        float[] vector = new float[dimensions];
        vector[hotIndex] = 1f;
        return vector;
    }
}
