package com.aiflow.engine.config;

import com.aiflow.engine.integration.HttpEndpointFactory;
import com.aiflow.engine.integration.OfflineEgressGuard;
import com.aiflow.engine.retrieval.TieredEmbeddingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** M5-A 的离线 profile 与出网门禁契约，不启动数据库或真实模型。 */
class OfflineProfileContractTest {

    @Test
    void offlineGuardOnlyAllowsLoopbackHttpEndpoints() {
        OfflineEgressGuard guard = new OfflineEgressGuard(new OfflineProperties(true, true,
                List.of("localhost", "127.0.0.1", "[::1]")));

        assertThat(guard.allows("local", "local", "http://localhost:11434/v1")).isTrue();
        assertThat(guard.allows("cloud", "cloud", "https://api.example.test/v1")).isFalse();
        assertThat(guard.allows("local", "local", "https://api.example.test/v1")).isFalse();
        assertThat(new OfflineEgressGuard(new OfflineProperties(true, true,
                List.of("api.example.test"))).allows("local", "local", "https://api.example.test/v1")).isFalse();
        assertThat(guard.allows("local", "local", "file:///tmp/model")).isFalse();
        assertThat(guard.allows("local", "local", "http://secret@localhost:11434/v1")).isFalse();
        assertThat(guard.allows("local", "local", "http://localhost:11434/v1?token=secret")).isFalse();
        assertThat(guard.allows("local", "local", "http://localhost:65536/v1")).isFalse();
        assertThat(new OfflineEgressGuard(new OfflineProperties(true, false,
                List.of("api.example.test"))).allows("local", "local", "https://api.example.test/v1")).isFalse();
        assertThat(guard.allows("local", "local", "http://LOCALHOST:11434/v1")).isTrue();
        assertThat(guard.rejectionReason()).doesNotContain("example.test");
    }

    @Test
    void offlineFactoryDoesNotRegisterRemoteChatProvidersEvenWhenEnabled() {
        ProviderProperties properties = providerProperties(Map.of(
                "cloud", endpoint("https://api.example.test/v1", "cloud"),
                "local", endpoint("http://127.0.0.1:11434/v1", "local")));

        HttpEndpointFactory factory = new HttpEndpointFactory(properties, new ObjectMapper(),
                new OfflineProperties(true, true, List.of("localhost", "127.0.0.1", "[::1]")));

        assertThat(factory.chatProviders()).containsKey("local").doesNotContainKey("cloud");
        assertThat(factory.chatEndpoints().stream().filter(view -> view.id().equals("local")).findFirst())
                .get().extracting(view -> view.health().available()).isEqualTo(false);
        assertThat(factory.chatEndpoints().stream().filter(view -> view.id().equals("cloud")).findFirst())
                .get().extracting(HttpEndpointFactory.EndpointView::enabled).isEqualTo(false);
        assertThat(factory.probes()).allMatch(probe -> probe.id().equals("local"));
    }

    @Test
    void offlineEmbeddingChainSkipsRemoteTiersAndKeepsLocalModelIdentity() {
        ProviderProperties properties = providerProperties(Map.of(
                "local", endpoint("http://localhost:11434/v1", "local")));
        HttpEndpointFactory factory = new HttpEndpointFactory(properties, new ObjectMapper(),
                new OfflineProperties(true, true, List.of("localhost", "127.0.0.1", "[::1]")));
        RetrievalProperties.Embedding embedding = new RetrievalProperties.Embedding("http", -1, 1024,
                "bge-m3", "http://localhost:11434/v1", "", 30_000,
                List.of(
                        new RetrievalProperties.EmbeddingTier("cloud", "https://api.example.test/v1",
                                "bge-m3", "", true, 30_000),
                        new RetrievalProperties.EmbeddingTier("local", "http://localhost:11434/v1",
                                "bge-m3", "", true, 30_000)));

        TieredEmbeddingProvider chain = RetrievalConfig.buildEmbeddingChain(embedding, factory,
                new OfflineProperties(true, true, List.of("localhost", "127.0.0.1", "[::1]")));

        assertThat(chain.tiers()).extracting(TieredEmbeddingProvider.Tier::name).containsExactly("local");
        assertThat(chain.id()).isEqualTo("http-embedding:bge-m3");
    }

    private static ProviderProperties providerProperties(Map<String, ProviderProperties.Endpoint> endpoints) {
        return new ProviderProperties("auto", new String[]{"local", "mock"}, List.of("local", "mock"),
                ProviderProperties.defaults().mock(), endpoints, ProviderProperties.defaults().probe(),
                ProviderProperties.defaults().breaker());
    }

    private static ProviderProperties.Endpoint endpoint(String baseUrl, String tier) {
        return new ProviderProperties.Endpoint(baseUrl, "demo-model", "", java.util.Set.of("chat"),
                true, 1_000, tier, "", 0);
    }
}
