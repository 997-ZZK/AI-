package com.aiflow.engine.voiceprint;

/** 归一化到 [0, 1] 的验证置信度区间；不表达具体算法分数或阈值。 */
public record VoiceprintConfidenceRange(double lowerInclusive, double upperInclusive) {

    public VoiceprintConfidenceRange {
        if (!Double.isFinite(lowerInclusive) || !Double.isFinite(upperInclusive)
                || lowerInclusive < 0.0 || upperInclusive > 1.0
                || lowerInclusive > upperInclusive) {
            throw new IllegalArgumentException("voiceprint confidence range must be within [0, 1]");
        }
    }

    public static VoiceprintConfidenceRange exact(double value) {
        return new VoiceprintConfidenceRange(value, value);
    }
}
