package com.aiflow.engine.retrieval;

import com.aiflow.engine.retrieval.mock.MockEmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockEmbeddingProviderTest {

    private final MockEmbeddingProvider provider = new MockEmbeddingProvider("mock-embedding", 1024);

    @Test
    void producesConfiguredDimensions() {
        assertThat(provider.embed("报销需要发票")).hasSize(1024);
        assertThat(provider.dimensions()).isEqualTo(1024);
        assertThat(provider.id()).isEqualTo("mock-embedding");
    }

    @Test
    void isDeterministicAcrossInstances() {
        float[] first = provider.embed("差旅住宿标准每晚五百元");
        float[] second = new MockEmbeddingProvider("another", 1024).embed("差旅住宿标准每晚五百元");
        assertThat(first).containsExactly(second);
    }

    @Test
    void ranksLexicalOverlapHigherThanUnrelatedText() {
        float[] query = provider.embed("发票抬头需要注明什么");
        float[] related = provider.embed("发票抬头必须为公司全称，并注明税号");
        float[] unrelated = provider.embed("值班表在周一同步，主值班负责响应告警");

        assertThat(Vectors.cosine(query, related)).isGreaterThan(Vectors.cosine(query, unrelated));
    }

    @Test
    void blankTextProducesZeroVector() {
        float[] vector = provider.embed("   ");
        assertThat(vector).hasSize(1024);
        assertThat(Vectors.cosine(vector, provider.embed("任意文本"))).isZero();
    }

    @Test
    void batchMatchesSingleEmbedding() {
        List<float[]> batch = provider.embedAll(List.of("第一条", "第二条"));
        assertThat(batch).hasSize(2);
        assertThat(batch.get(1)).containsExactly(provider.embed("第二条"));
    }
}
