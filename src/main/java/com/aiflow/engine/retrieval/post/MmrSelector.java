package com.aiflow.engine.retrieval.post;

import com.aiflow.engine.retrieval.RetrievedChunk;
import com.aiflow.engine.retrieval.Vectors;

import java.util.ArrayList;
import java.util.List;

/**
 * MMR（最大边际相关性）去冗余：在「分数高」与「和已选切片不重复」之间权衡。
 * 无向量的切片（多为云端知识库未回传向量）冗余度按 0 计，等价于按分数参与竞争。
 */
public final class MmrSelector {

    private MmrSelector() {
    }

    public static List<RetrievedChunk> apply(List<RetrievedChunk> candidates, int limit, double lambda) {
        if (candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<RetrievedChunk> remaining = new ArrayList<>(candidates);
        List<RetrievedChunk> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < limit) {
            int bestIndex = -1;
            double bestValue = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < remaining.size(); index++) {
                RetrievedChunk candidate = remaining.get(index);
                double redundancy = maxSimilarity(candidate, selected);
                double value = lambda * candidate.score() - (1 - lambda) * redundancy;
                if (value > bestValue) {
                    bestValue = value;
                    bestIndex = index;
                }
            }
            selected.add(remaining.remove(bestIndex));
        }
        return List.copyOf(selected);
    }

    private static double maxSimilarity(RetrievedChunk candidate, List<RetrievedChunk> selected) {
        if (candidate.embedding() == null) {
            return 0;
        }
        double max = 0;
        for (RetrievedChunk chosen : selected) {
            if (chosen.embedding() == null) {
                continue;
            }
            max = Math.max(max, Vectors.cosine(candidate.embedding(), chosen.embedding()));
        }
        return max;
    }
}
