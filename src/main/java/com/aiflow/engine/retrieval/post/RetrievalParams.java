package com.aiflow.engine.retrieval.post;

/**
 * 检索后处理参数。
 *
 * @param minScore      绝对阈值：归一化分数低于它的切片直接丢弃
 * @param topK          最终希望保留的切片数（MMR 选择的目标数量）
 * @param minK          断崖截断的下限：无论断崖多陡，至少保留这么多
 * @param maxK          进入 MMR 的候选上限（>= topK，防止云端一次召回过多）
 * @param cliffRatio    相对跌幅阈值：相邻分数跌幅 >= 该比例即视为断崖
 * @param mmrLambda     相关性与多样性的权衡（1.0 = 只按分数，0.0 = 只按多样性）
 * @param mergeAdjacent 是否合并同文档相邻切片
 */
public record RetrievalParams(
        double minScore,
        int topK,
        int minK,
        int maxK,
        double cliffRatio,
        double mmrLambda,
        boolean mergeAdjacent
) {

    public RetrievalParams {
        if (minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("minScore 必须落在 [0,1]: " + minScore);
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正整数: " + topK);
        }
        if (minK <= 0) {
            throw new IllegalArgumentException("minK 必须为正整数: " + minK);
        }
        if (maxK < minK) {
            throw new IllegalArgumentException("maxK 不能小于 minK: " + maxK + " < " + minK);
        }
        if (cliffRatio <= 0 || cliffRatio >= 1) {
            throw new IllegalArgumentException("cliffRatio 必须落在 (0,1): " + cliffRatio);
        }
        if (mmrLambda < 0 || mmrLambda > 1) {
            throw new IllegalArgumentException("mmrLambda 必须落在 [0,1]: " + mmrLambda);
        }
        maxK = Math.max(maxK, topK);
    }

    public static RetrievalParams defaults() {
        return new RetrievalParams(0.35, 6, 1, 8, 0.35, 0.7, true);
    }

    public RetrievalParams withTopK(Integer override) {
        if (override == null || override <= 0) {
            return this;
        }
        return new RetrievalParams(minScore, override, Math.min(minK, override), Math.max(maxK, override),
                cliffRatio, mmrLambda, mergeAdjacent);
    }

    public RetrievalParams withMinScore(Double override) {
        if (override == null) {
            return this;
        }
        return new RetrievalParams(override, topK, minK, maxK, cliffRatio, mmrLambda, mergeAdjacent);
    }
}
