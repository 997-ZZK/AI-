package com.aiflow.engine.retrieval.post;

import com.aiflow.engine.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 后处理链（顺序固定，便于回归对比）：
 * 排序 -> 阈值过滤 -> 断崖 top-k -> MMR -> 相邻合并。
 */
public final class RetrievalPipeline {

    private RetrievalPipeline() {
    }

    public static ProcessedRetrieval process(List<RetrievedChunk> candidates, RetrievalParams params) {
        List<RetrievedChunk> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                .thenComparing(chunk -> chunk.docId() == null ? "" : chunk.docId())
                .thenComparingInt(RetrievedChunk::chunkIndex));
        List<RetrievedChunk> afterThreshold = ThresholdFilter.apply(ranked, params.minScore());
        List<RetrievedChunk> afterTopK = CliffTopK.apply(afterThreshold, params.minK(), params.maxK(), params.cliffRatio());
        List<RetrievedChunk> afterMmr = MmrSelector.apply(afterTopK, params.topK(), params.mmrLambda());
        List<RetrievedChunk> afterMerge = params.mergeAdjacent()
                ? AdjacentMerger.apply(afterMmr, AdjacentMerger.DEFAULT_MAX_MERGED_CHARS)
                : afterMmr;
        List<RetrievedChunk> finalChunks = new ArrayList<>(afterMerge);
        finalChunks.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                .thenComparing(chunk -> chunk.docId() == null ? "" : chunk.docId())
                .thenComparingInt(RetrievedChunk::chunkIndex));
        RetrievalStats stats = new RetrievalStats(ranked.size(), afterThreshold.size(), afterTopK.size(),
                afterMmr.size(), finalChunks.size());
        return new ProcessedRetrieval(List.copyOf(finalChunks), stats);
    }
}
