package com.aiflow.engine.retrieval;

/**
 * 云端知识库分数：不同厂商量纲不同（0..1 相似度、0..100 打分、甚至负值），
 * 用配置的量程做线性映射，保持绝对阈值语义（不做批内 min-max，那会破坏「低分丢弃」）。
 */
public final class LinearRangeScoreNormalizer implements ScoreNormalizer {

    private final String source;
    private final double rangeMin;
    private final double rangeMax;

    public LinearRangeScoreNormalizer(String source, double rangeMin, double rangeMax) {
        this.source = source;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public double normalize(double rawScore) {
        if (rangeMax - rangeMin < 1e-9) {
            return CosineScoreNormalizer.clamp(rawScore);
        }
        return CosineScoreNormalizer.clamp((rawScore - rangeMin) / (rangeMax - rangeMin));
    }
}
