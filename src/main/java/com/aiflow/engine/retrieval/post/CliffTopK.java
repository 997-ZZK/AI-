package com.aiflow.engine.retrieval.post;

import com.aiflow.engine.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 动态 top-k：先按分数降序，再找「相似度断崖」切一刀。
 * 断崖定义：相邻两名归一化分数的相对跌幅 >= cliffRatio；截断位置最多 maxK（保护上限），
 * 且无论断崖多陡都至少保留 minK（保护下限）——断崖落在保护区内时按 minK 截断，而不是继续往后找。
 */
public final class CliffTopK {

    private CliffTopK() {
    }

    public static List<RetrievedChunk> apply(List<RetrievedChunk> ranked, int minK, int maxK, double cliffRatio) {
        List<RetrievedChunk> sorted = new ArrayList<>(ranked);
        sorted.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                .thenComparing(chunk -> chunk.docId() == null ? "" : chunk.docId())
                .thenComparingInt(RetrievedChunk::chunkIndex));
        int limit = Math.min(sorted.size(), maxK);
        int cut = limit;
        for (int index = 0; index < limit - 1; index++) {
            double current = sorted.get(index).score();
            double next = sorted.get(index + 1).score();
            if (current <= 0) {
                continue;
            }
            double drop = (current - next) / current;
            if (drop >= cliffRatio) {
                cut = Math.max(minK, index + 1);
                break;
            }
        }
        return List.copyOf(sorted.subList(0, Math.min(cut, limit)));
    }
}
