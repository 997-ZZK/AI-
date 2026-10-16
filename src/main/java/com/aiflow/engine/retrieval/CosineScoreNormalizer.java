package com.aiflow.engine.retrieval;

/**
 * 本地余弦分数：cos in [-1,1] -> max(0, cos) in [0,1]。
 *
 * 刻意不用 (cos + 1) / 2：那会把「完全不相关」（cos = 0）映射成 0.5，永远高于阈值，
 * 「相似度太低就丢弃」直接失效。压到 0 之后阈值才有绝对含义（0.35 ≈ cos >= 0.35）。
 */
public final class CosineScoreNormalizer implements ScoreNormalizer {

    private final String source;

    public CosineScoreNormalizer(String source) {
        this.source = source;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public double normalize(double cosine) {
        return clamp(Math.max(0, cosine));
    }

    static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
