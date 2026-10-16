package com.aiflow.engine.retrieval.post;

import com.aiflow.engine.retrieval.RetrievedChunk;
import com.aiflow.engine.retrieval.TestChunks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalPipelineTest {

    private static final RetrievalParams PARAMS = RetrievalParams.defaults();

    @Test
    void dropsChunksBelowThreshold() {
        // 只验证阈值阶段：把断崖比率推到接近 1、关掉 MMR 与相邻合并，避免其他阶段影响结论
        RetrievalParams thresholdOnly = new RetrievalParams(0.35, 6, 1, 8, 0.99, 1.0, false);
        ProcessedRetrieval result = RetrievalPipeline.process(List.of(
                TestChunks.chunk("doc-a", 0, "甲", 0.90),
                TestChunks.chunk("doc-b", 0, "乙", 0.50),
                TestChunks.chunk("doc-c", 0, "丙", 0.20)), thresholdOnly);

        assertThat(result.chunks()).extracting(RetrievedChunk::docId).containsExactly("doc-a", "doc-b");
        assertThat(result.stats().raw()).isEqualTo(3);
        assertThat(result.stats().afterThreshold()).isEqualTo(2);
    }

    @Test
    void cutsAtSimilarityCliff() {
        RetrievalParams params = new RetrievalParams(0.0, 6, 1, 8, 0.35, 1.0, false);
        ProcessedRetrieval result = RetrievalPipeline.process(List.of(
                TestChunks.chunk("doc-a", 0, "甲", 0.90),
                TestChunks.chunk("doc-b", 0, "乙", 0.88),
                TestChunks.chunk("doc-c", 0, "丙", 0.40),
                TestChunks.chunk("doc-d", 0, "丁", 0.39)), params);

        assertThat(result.chunks()).extracting(RetrievedChunk::docId).containsExactly("doc-a", "doc-b");
        assertThat(result.stats().afterTopK()).isEqualTo(2);
    }

    @Test
    void keepsMinKWhenTheCliffIsTheFirstGap() {
        // 断崖发生在第 1、2 名之间（保护区）时按 minK 截断，而不是忽略断崖把 3 条都带进提示词
        RetrievalParams params = new RetrievalParams(0.0, 6, 2, 8, 0.35, 1.0, false);
        ProcessedRetrieval result = RetrievalPipeline.process(List.of(
                TestChunks.chunk("doc-a", 0, "甲", 0.90),
                TestChunks.chunk("doc-b", 0, "乙", 0.10),
                TestChunks.chunk("doc-c", 0, "丙", 0.09)), params);

        assertThat(result.chunks()).extracting(RetrievedChunk::docId).containsExactly("doc-a", "doc-b");
        assertThat(result.stats().afterTopK()).isEqualTo(2);
    }

    @Test
    void respectsMaxKCap() {
        RetrievalParams params = new RetrievalParams(0.0, 3, 1, 3, 0.9, 1.0, false);
        ProcessedRetrieval result = RetrievalPipeline.process(List.of(
                TestChunks.chunk("doc-a", 0, "甲", 0.90),
                TestChunks.chunk("doc-b", 0, "乙", 0.89),
                TestChunks.chunk("doc-c", 0, "丙", 0.88),
                TestChunks.chunk("doc-d", 0, "丁", 0.87),
                TestChunks.chunk("doc-e", 0, "戊", 0.86)), params);

        assertThat(result.chunks()).hasSize(3);
    }

    @Test
    void mmrPrefersDiverseChunkOverNearDuplicate() {
        float[] axisZero = TestChunks.oneHot(8, 0);
        float[] axisOne = TestChunks.oneHot(8, 1);
        RetrievalParams params = new RetrievalParams(0.0, 2, 1, 8, 0.5, 0.5, false);

        ProcessedRetrieval result = RetrievalPipeline.process(List.of(
                TestChunks.chunk("local", "doc-a", 0, "甲", 0.95, axisZero),
                TestChunks.chunk("local", "doc-b", 0, "乙", 0.94, axisZero),
                TestChunks.chunk("local", "doc-c", 0, "丙", 0.90, axisOne)), params);

        assertThat(result.chunks()).extracting(RetrievedChunk::docId).containsExactly("doc-a", "doc-c");
    }

    @Test
    void mergesAdjacentChunksOfSameDocument() {
        ProcessedRetrieval result = RetrievalPipeline.process(List.of(
                TestChunks.chunk("doc-a", 0, "第一句", 0.90),
                TestChunks.chunk("doc-a", 1, "第二句", 0.80),
                TestChunks.chunk("doc-b", 0, "别的文档", 0.70)), PARAMS);

        assertThat(result.chunks()).hasSize(2);
        RetrievedChunk merged = result.chunks().get(0);
        assertThat(merged.docId()).isEqualTo("doc-a");
        assertThat(merged.text()).isEqualTo("第一句\n第二句");
        assertThat(merged.score()).isEqualTo(0.90);
        assertThat(result.stats().afterMerge()).isEqualTo(2);
    }

    @Test
    void validatesParams() {
        assertThatThrownBy(() -> new RetrievalParams(1.5, 6, 1, 8, 0.35, 0.7, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalParams(0.3, 0, 1, 8, 0.35, 0.7, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalParams(0.3, 6, 3, 2, 0.35, 0.7, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new RetrievalParams(0.3, 6, 1, 2, 0.35, 0.7, true).maxK()).isEqualTo(6);
    }
}
