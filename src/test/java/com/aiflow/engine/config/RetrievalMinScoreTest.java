package com.aiflow.engine.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索阈值的解析规则：{@code embedding.min-score} 优先，未配置（{@code < 0}）才落回全局。
 *
 * <p>钉死这条规则的原因：阈值用错量纲不会抛异常，只会让「低相似度丢弃」悄悄失效。
 * 切换向量模型时必须连阈值一起换，所以阈值挂在 embedding 配置块里。
 */
class RetrievalMinScoreTest {

    @Test
    void embeddingThresholdWinsSoSwitchingVectorModelCannotKeepTheOldScale() {
        assertThat(RetrievalConfig.effectiveMinScore(properties(0.35, 0.55))).isEqualTo(0.55);
    }

    @Test
    void unsetEmbeddingThresholdInheritsTheGlobalOne() {
        assertThat(RetrievalConfig.effectiveMinScore(properties(0.42, -1))).isEqualTo(0.42);
    }

    @Test
    void explicitZeroIsHonouredRatherThanTreatedAsUnset() {
        assertThat(RetrievalConfig.effectiveMinScore(properties(0.35, 0))).isZero();
    }

    @Test
    void defaultsStayOnTheMockCalibratedThreshold() {
        assertThat(RetrievalConfig.effectiveMinScore(RetrievalProperties.defaults())).isEqualTo(0.35);
    }

    private static RetrievalProperties properties(double globalMinScore, double embeddingMinScore) {
        RetrievalProperties base = RetrievalProperties.defaults();
        RetrievalProperties.Embedding embedding = base.embedding();
        return new RetrievalProperties(globalMinScore, base.topK(), base.minK(), base.maxK(), base.cliffRatio(),
                base.mmrLambda(), base.mergeAdjacent(), base.sources(), base.embeddingStore(),
                new RetrievalProperties.Embedding(embedding.mode(), embeddingMinScore, embedding.dimensions(),
                        embedding.model(), embedding.baseUrl(), embedding.apiKey(), embedding.timeoutMs(),
                        embedding.tiers()),
                base.cloud(), base.ann());
    }
}