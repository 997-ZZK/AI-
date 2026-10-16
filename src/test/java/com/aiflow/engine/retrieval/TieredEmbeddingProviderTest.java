package com.aiflow.engine.retrieval;

import com.aiflow.engine.provider.EndpointHealth;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分级 embedding：前档失败落下一档、熔断档位被跳过、失败原因要能列全。
 *
 * <p>最容易被忽略的是身份问题：无论哪一档服务了这一票，对外声明的 id 必须是同一个模型身份，
 * 否则同一份索引在「云端加速」与「本地兜底」之间切换时会被判成两个向量空间。
 */
class TieredEmbeddingProviderTest {

    private static final String CHAIN_ID = "http-embedding:bge-m3";
    private static final List<String> TEXTS = List.of("报销制度的发票要求");

    @Test
    void fallsBackToSecondTierAndMarksFirstDown() {
        EndpointHealth cloudHealth = new EndpointHealth("embedding:cloud", true, 3, 1_000);
        EndpointHealth localHealth = new EndpointHealth("embedding:local", true, 3, 1_000);
        TieredEmbeddingProvider provider = chain(
                tier("cloud", "cloud", failing("云端 502"), cloudHealth),
                tier("local", "local", ok(), localHealth));

        List<float[]> vectors = provider.embedAll(TEXTS);

        assertThat(vectors).hasSize(1);
        assertThat(vectors.get(0)).hasSize(4);
        assertThat(cloudHealth.snapshot().state()).isEqualTo("down");
        assertThat(cloudHealth.snapshot().lastError()).contains("云端 502");
        assertThat(localHealth.snapshot().state()).isEqualTo("up");
    }

    @Test
    void keepsCanonicalIdentityAndDimensionsWhateverTierServes() {
        TieredEmbeddingProvider provider = chain(
                tier("cloud", "cloud", failing("云端 502"), new EndpointHealth("embedding:cloud", true, 3, 1_000)),
                tier("local", "local", ok(), new EndpointHealth("embedding:local", true, 3, 1_000)));

        provider.embedAll(TEXTS);

        assertThat(provider.id()).isEqualTo(CHAIN_ID);
        assertThat(provider.dimensions()).isEqualTo(4);
    }

    @Test
    void throwsWithEveryTierReasonWhenAllFail() {
        TieredEmbeddingProvider provider = chain(
                tier("cloud", "cloud", failing("云端 502"), new EndpointHealth("embedding:cloud", true, 3, 1_000)),
                tier("local", "local", failing("本地模型未拉取"),
                        new EndpointHealth("embedding:local", true, 3, 1_000)));

        assertThatThrownBy(() -> provider.embedAll(TEXTS))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("云端 502")
                .hasMessageContaining("本地模型未拉取");
    }

    @Test
    void skipsTierWhoseCircuitIsOpen() {
        AtomicInteger cloudCalls = new AtomicInteger();
        EndpointHealth cloudHealth = new EndpointHealth("embedding:cloud", true, 1, 60_000);
        EmbeddingProvider cloud = new EmbeddingProvider() {
            @Override
            public String id() {
                return CHAIN_ID;
            }

            @Override
            public int dimensions() {
                return 4;
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                cloudCalls.incrementAndGet();
                throw new RetrievalException("云端不可达");
            }
        };
        TieredEmbeddingProvider provider = chain(
                tier("cloud", "cloud", cloud, cloudHealth),
                tier("local", "local", ok(), new EndpointHealth("embedding:local", true, 1, 60_000)));

        provider.embedAll(TEXTS);
        provider.embedAll(TEXTS);

        assertThat(cloudCalls.get()).as("熔断打开后不该继续撞超时").isEqualTo(1);
        assertThat(provider.healthy()).as("本地档还在，整条链仍然可用").isTrue();
    }

    @Test
    void reportsUnhealthyWhenEveryTierIsOpen() {
        EndpointHealth cloudHealth = new EndpointHealth("embedding:cloud", true, 1, 60_000);
        EndpointHealth localHealth = new EndpointHealth("embedding:local", true, 1, 60_000);
        TieredEmbeddingProvider provider = chain(
                tier("cloud", "cloud", failing("云端不可达"), cloudHealth),
                tier("local", "local", failing("本地不可达"), localHealth));

        assertThatThrownBy(() -> provider.embedAll(TEXTS)).isInstanceOf(RetrievalException.class);
        assertThat(provider.healthy()).isFalse();
        assertThat(provider.status()).hasSize(2);
    }

    private static TieredEmbeddingProvider chain(TieredEmbeddingProvider.Tier... tiers) {
        return new TieredEmbeddingProvider(CHAIN_ID, 4, Arrays.asList(tiers));
    }

    private static TieredEmbeddingProvider.Tier tier(String name, String kind, EmbeddingProvider provider,
                                                    EndpointHealth health) {
        return new TieredEmbeddingProvider.Tier(name, kind, provider, health, "http://localhost:11434/v1");
    }

    private static EmbeddingProvider ok() {
        return new EmbeddingProvider() {
            @Override
            public String id() {
                return CHAIN_ID;
            }

            @Override
            public int dimensions() {
                return 4;
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                return texts.stream().map(text -> new float[]{0.1f, 0.2f, 0.3f, 0.4f}).toList();
            }
        };
    }

    private static EmbeddingProvider failing(String message) {
        return new EmbeddingProvider() {
            @Override
            public String id() {
                return CHAIN_ID;
            }

            @Override
            public int dimensions() {
                return 4;
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                throw new RetrievalException(message);
            }
        };
    }
}