package com.aiflow.engine.retrieval;

import com.aiflow.engine.retrieval.mock.MockEmbeddingProvider;
import com.aiflow.engine.retrieval.post.RetrievalParams;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagRetrievalServiceTest {

    private final MockEmbeddingProvider embeddings = new MockEmbeddingProvider("mock-embedding", 64);

    @Test
    void normalizesLocalAndCloudOntoTheSameScale() {
        RetrievalProvider local = stub("local", query ->
                List.of(TestChunks.chunk("local", "doc-a", 0, "同一段内容", 0.85, null)));
        RetrievalProvider cloud = stub("cloud", query ->
                List.of(TestChunks.chunk("cloud", "doc-a", 0, "同一段内容", 85.0, null)));

        RetrievalResult result = service(List.of(local, cloud)).retrieve("问题", "kb-1", Set.of(), null, null);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).chunk().score()).isCloseTo(0.85, Offset.offset(1e-9));
        assertThat(result.chunks().get(0).chunk().contentHash())
                .isEqualTo(result.chunks().get(0).chunk().contentHash());
    }

    @Test
    void keepsFirstRegisteredSourceWhenScoresTieWithinFloatNoise() {
        // 云端 50.00000000000001 经量程归一化后是 0.5000000000000001，比本地 0.5 高 1e-16（浮点噪声），且都在阈值之上
        RetrievalProvider local = stub("local", query ->
                List.of(TestChunks.chunk("local", "doc-a", 0, "同一段内容", 0.5, null)));
        RetrievalProvider cloud = stub("cloud", query ->
                List.of(TestChunks.chunk("cloud", "doc-a", 0, "同一段内容", 50.00000000000001, null)));

        RetrievalResult result = service(List.of(local, cloud)).retrieve("问题", "kb-1", Set.of(), null, null);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).chunk().source())
                .as("云端量程往返只差几十飞分之一，归属不该随机漂移")
                .isEqualTo("local");
    }

    @Test
    void keepsHigherScoringSourceWhenScoreDifferenceIsReal() {
        RetrievalProvider local = stub("local", query ->
                List.of(TestChunks.chunk("local", "doc-a", 0, "同一段内容", 0.30, null)));
        RetrievalProvider cloud = stub("cloud", query ->
                List.of(TestChunks.chunk("cloud", "doc-a", 0, "同一段内容", 90.0, null)));

        RetrievalResult result = service(List.of(local, cloud)).retrieve("问题", "kb-1", Set.of(), null, null);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).chunk().source()).isEqualTo("cloud");
    }

    @Test
    void fusesSourcesAndRanksByNormalizedScore() {
        RetrievalProvider local = stub("local", query ->
                List.of(TestChunks.chunk("local", "doc-local", 0, "本地命中的内容", 0.60, null)));
        RetrievalProvider cloud = stub("cloud", query ->
                List.of(TestChunks.chunk("cloud", "doc-cloud", 0, "云端命中的内容", 90.0, null)));

        RetrievalResult result = service(List.of(local, cloud)).retrieve("问题", "kb-1", Set.of(), null, null);

        assertThat(result.sources()).containsExactly("local", "cloud");
        assertThat(result.chunks().get(0).chunk().docId()).isEqualTo("doc-cloud");
        assertThat(result.chunks().get(0).chunk().score()).isCloseTo(0.9, Offset.offset(1e-9));
    }

    @Test
    void keepsWorkingWhenOneSourceFails() {
        RetrievalProvider local = stub("local", query ->
                List.of(TestChunks.chunk("local", "doc-a", 0, "本地内容", 0.8, null)));
        RetrievalProvider broken = stub("cloud", query -> {
            throw new RetrievalException("知识库超时");
        });

        RetrievalResult result = service(List.of(local, broken)).retrieve("问题", "kb-1", Set.of(), null, null);

        assertThat(result.sources()).containsExactly("local");
        assertThat(result.chunks()).hasSize(1);
    }

    @Test
    void failsWhenEverySourceIsDown() {
        RetrievalProvider broken = stub("local", query -> {
            throw new RetrievalException("向量库不可用");
        });

        assertThatThrownBy(() -> service(List.of(broken)).retrieve("问题", "kb-1", Set.of(), null, null))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("所有检索来源都不可用");
    }

    @Test
    void honorsSourceFilter() {
        AtomicInteger localCalls = new AtomicInteger();
        RetrievalProvider local = stub("local", query -> {
            localCalls.incrementAndGet();
            return List.of(TestChunks.chunk("local", "doc-a", 0, "本地内容", 0.8, null));
        });
        RetrievalProvider cloud = stub("cloud", query ->
                List.of(TestChunks.chunk("cloud", "doc-b", 0, "云端内容", 70.0, null)));

        RetrievalResult result = service(List.of(local, cloud)).retrieve("问题", "kb-1", Set.of("cloud"), null, null);

        assertThat(localCalls.get()).isZero();
        assertThat(result.sources()).containsExactly("cloud");
    }

    @Test
    void rejectsVectorDimensionMismatch() {
        EmbeddingProvider broken = new EmbeddingProvider() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public int dimensions() {
                return 1024;
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                List<float[]> vectors = new ArrayList<>();
                for (int index = 0; index < texts.size(); index++) {
                    vectors.add(new float[8]);
                }
                return vectors;
            }
        };
        RagRetrievalService service = new RagRetrievalService(broken,
                List.of(stub("local", query -> List.of())), List.of(new CosineScoreNormalizer("local")),
                RetrievalParams.defaults(), new ContextPromptAssembler());

        assertThatThrownBy(() -> service.retrieve("问题", "kb-1", Set.of(), null, null))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("维度不符");
    }

    @Test
    void appliesRequestOverridesForTopKAndThreshold() {
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            chunks.add(TestChunks.chunk("local", "doc-" + index, 0, "内容" + index, 0.9 - index * 0.05, null));
        }
        RetrievalResult result = service(List.of(stub("local", query -> chunks)))
                .retrieve("问题", "kb-1", Set.of(), 2, 0.99);

        assertThat(result.chunks()).isEmpty();
    }

    private RagRetrievalService service(List<RetrievalProvider> providers) {
        return new RagRetrievalService(embeddings, providers,
                List.of(new CosineScoreNormalizer("local"), new LinearRangeScoreNormalizer("cloud", 0, 100)),
                RetrievalParams.defaults(), new ContextPromptAssembler());
    }

    private static RetrievalProvider stub(String source, Function<RetrievalQuery, List<RetrievedChunk>> behavior) {
        return new RetrievalProvider() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public List<RetrievedChunk> retrieve(RetrievalQuery query) {
                return behavior.apply(query);
            }
        };
    }
}
