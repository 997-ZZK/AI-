package com.aiflow.engine.api;

import com.aiflow.engine.config.OfflineProperties;
import com.aiflow.engine.integration.HttpEndpointFactory;
import com.aiflow.engine.integration.HttpKnowledgeBaseProvider;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.RetrievalProvider;
import com.aiflow.engine.retrieval.TieredEmbeddingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离线 profile 的真实 Spring 装配验收。
 *
 * <p>端点指向一个随机的、未监听的 loopback 端口：测试不依赖 Ollama、真实模型或外网，
 * 但仍会启动完整应用上下文，验证云端 provider 被隔离、首次探活前本地档位不可用，
 * 以及能力 API 与实际 provider 装配保持一致。
 */
@SpringBootTest(properties = {
        "aiflow.provider.probe.initial-delay-ms=600000",
        "aiflow.provider.probe.interval-ms=600000"
})
@ActiveProfiles("offline")
@AutoConfigureMockMvc
class OfflineProfileIntegrationTest {

    private static final int UNUSED_PORT = reserveUnusedPort();
    private static final String LOOPBACK_BASE_URL = "http://127.0.0.1:" + UNUSED_PORT + "/v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private OfflineProperties offlineProperties;

    @Autowired
    private HttpEndpointFactory endpoints;

    @Autowired
    private ProviderRouter router;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @Autowired
    private List<RetrievalProvider> retrievalProviders;

    @DynamicPropertySource
    static void offlineLoopbackEndpoint(DynamicPropertyRegistry registry) {
        registry.add("aiflow.provider.mode", () -> "auto");
        registry.add("aiflow.provider.order", () -> "local,mock");
        registry.add("aiflow.provider.endpoints.local.base-url", () -> LOOPBACK_BASE_URL);
        registry.add("aiflow.retrieval.embedding.mode", () -> "http");
        registry.add("aiflow.retrieval.embedding.base-url", () -> LOOPBACK_BASE_URL);
        registry.add("aiflow.retrieval.embedding.tiers[0].base-url", () -> LOOPBACK_BASE_URL);
    }

    @Test
    void activatesOfflineProfileAndKeepsRemoteProvidersOut() {
        assertThat(offlineProperties.enabled()).isTrue();
        assertThat(offlineProperties.allows(LOOPBACK_BASE_URL)).isTrue();
        assertThat(endpoints.egressGuard().allows("cloud", "cloud", "https://example.test/v1")).isFalse();

        assertThat(endpoints.chatProviders()).containsKey("local").doesNotContainKeys("cloud", "ark", "qwen", "deepseek");
        assertThat(router.providerIds()).contains("local", "mock").doesNotContain("cloud", "ark", "qwen", "deepseek");

        List<HttpEndpointFactory.EndpointView> cloudViews = endpoints.chatEndpoints().stream()
                .filter(view -> List.of("cloud", "ark", "qwen", "deepseek").contains(view.id()))
                .toList();
        assertThat(cloudViews).allSatisfy(view -> assertThat(view.enabled()).isFalse());

        assertThat(applicationContext.getBeansOfType(HttpKnowledgeBaseProvider.class)).isEmpty();
        assertThat(retrievalProviders).noneMatch(provider -> "cloud".equals(provider.source()));
    }

    @Test
    void reportsPendingLocalChatAndEmbeddingAsUnavailableBeforeFirstProbe() throws Exception {
        HttpEndpointFactory.EndpointView localChat = endpoints.chatEndpoints().stream()
                .filter(view -> "local".equals(view.id())).findFirst().orElseThrow();
        assertThat(localChat.health().snapshot().state()).isEqualTo("down");
        assertThat(localChat.health().available()).isFalse();

        assertThat(embeddingProvider).isInstanceOf(TieredEmbeddingProvider.class);
        TieredEmbeddingProvider tiered = (TieredEmbeddingProvider) embeddingProvider;
        assertThat(tiered.healthy()).isFalse();
        assertThat(tiered.tiers()).singleElement().satisfies(tier -> {
            assertThat(tier.kind()).isEqualTo("local");
            assertThat(tier.health().snapshot().state()).isEqualTo("down");
            assertThat(tier.health().available()).isFalse();
        });

        JsonNode data = getData("/api/v1/capabilities");
        JsonNode chat = channel(data, "chat");
        assertThat(chat.path("activeProvider").asText()).isEqualTo("mock");
        assertThat(candidate(chat, "local").path("available").asBoolean()).isFalse();
        assertThat(candidate(chat, "mock").path("available").asBoolean()).isTrue();

        JsonNode embedding = channel(data, "embedding");
        assertThat(embedding.path("status").asText()).isEqualTo("unavailable");
        assertThat(embedding.path("activeProvider").isNull()).isTrue();
        assertThat(embedding.path("candidates")).hasSize(1);
        assertThat(embedding.path("candidates").get(0).path("available").asBoolean()).isFalse();

        JsonNode cloudKb = channel(data, "cloud-knowledge-base");
        assertThat(cloudKb.path("status").asText()).isEqualTo("unavailable");
        assertThat(cloudKb.path("activeProvider").isNull()).isTrue();
        assertThat(cloudKb.path("candidates").get(0).path("expected").asBoolean()).isFalse();
    }

    private JsonNode getData(String path) throws Exception {
        String body = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }

    private JsonNode channel(JsonNode data, String name) {
        for (JsonNode channel : data.path("channels")) {
            if (name.equals(channel.path("channel").asText())) {
                return channel;
            }
        }
        throw new AssertionError("能力报告里没有 " + name + " 通道: " + data);
    }

    private JsonNode candidate(JsonNode channel, String id) {
        for (JsonNode candidate : channel.path("candidates")) {
            if (id.equals(candidate.path("id").asText())) {
                return candidate;
            }
        }
        throw new AssertionError("通道 " + channel.path("channel").asText() + " 没有候选 " + id + ": " + channel);
    }

    private static int reserveUnusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
