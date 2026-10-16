package com.aiflow.engine.api;

import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * offline profile 的 loopback 成功路径：用合成的 OpenAI 兼容桩验证 local chat、embedding 和 RAG。
 * 不依赖 Ollama/vLLM 或真实模型，也不产生外部网络请求。
 */
@SpringBootTest(properties = {
        "aiflow.provider.probe.enabled=false",
        "aiflow.retrieval.embedding-store=memory",
        "aiflow.retrieval.embedding.dimensions=4"
})
@ActiveProfiles("offline")
@AutoConfigureMockMvc
class OfflineLoopbackE2eIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL = "offline-loopback-model";
    private static final AtomicInteger CHAT_REQUESTS = new AtomicInteger();
    private static final AtomicInteger EMBEDDING_REQUESTS = new AtomicInteger();
    private static final HttpServer STUB = startStub();

    @Autowired
    private ProviderRouter router;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @DynamicPropertySource
    static void localEndpoint(DynamicPropertyRegistry registry) {
        String baseUrl = "http://127.0.0.1:" + STUB.getAddress().getPort() + "/v1";
        registry.add("aiflow.provider.mode", () -> "auto");
        registry.add("aiflow.provider.order", () -> "local,mock");
        registry.add("aiflow.provider.endpoints.local.base-url", () -> baseUrl);
        registry.add("aiflow.provider.endpoints.local.model", () -> MODEL);
        registry.add("aiflow.retrieval.embedding.mode", () -> "http");
        registry.add("aiflow.retrieval.embedding.model", () -> MODEL);
        registry.add("aiflow.retrieval.embedding.base-url", () -> baseUrl);
        registry.add("aiflow.retrieval.embedding.tiers[0].base-url", () -> baseUrl);
        registry.add("aiflow.retrieval.embedding.tiers[0].model", () -> MODEL);
    }

    @Test
    void localChatAndEmbeddingUseTheLoopbackProvider() {
        assertThat(router.route(Set.of(Capabilities.CHAT)).id()).isEqualTo("local");

        int chatBefore = CHAT_REQUESTS.get();
        int embeddingBefore = EMBEDDING_REQUESTS.get();
        ProviderRouter.ChatOutcome chat = router.chat(Set.of(Capabilities.CHAT),
                new ChatRequest(null, List.of(ChatMessage.user("离线联调"))), ignored -> { });
        assertThat(chat.providerId()).isEqualTo("local");
        assertThat(chat.response().content()).isEqualTo("本地 loopback 桩回答");

        List<float[]> vectors = embeddingProvider.embedAll(List.of("第一条", "第二条"));
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1.0f, 0.0f, 0.0f, 0.0f);
        assertThat(CHAT_REQUESTS.get()).isEqualTo(chatBefore + 1);
        assertThat(EMBEDDING_REQUESTS.get()).isEqualTo(embeddingBefore + 1);
    }

    @Test
    void capabilityReportShowsLocalChatAndEmbeddingAsActive() throws Exception {
        JsonNode data = getData("/api/v1/capabilities");

        JsonNode chat = channel(data, "chat");
        assertThat(chat.path("status").asText()).isEqualTo("ok");
        assertThat(chat.path("activeProvider").asText()).isEqualTo("local");
        assertThat(chat.path("activeTier").asText()).isEqualTo("local");

        JsonNode embedding = channel(data, "embedding");
        assertThat(embedding.path("status").asText()).isEqualTo("ok");
        assertThat(embedding.path("activeProvider").asText()).isEqualTo("http-embedding:" + MODEL);
        assertThat(data.path("level").asText()).isEqualTo("L1");

        JsonNode cloudKb = channel(data, "cloud-knowledge-base");
        assertThat(cloudKb.path("status").asText()).isEqualTo("unavailable");
    }

    @Test
    void ragApiUsesLoopbackEmbeddingAndReturnsCitation() throws Exception {
        String kbId = "offline-loopback-" + UUID.randomUUID();
        JsonNode ingested = postData("/api/v1/kbs/" + kbId + "/documents",
                Map.of("title", "离线制度.md", "content", "离线环境只允许 loopback provider。", "sourceType", "MARKDOWN"));
        assertThat(ingested.path("status").asText()).isEqualTo("INDEXED");

        JsonNode retrieved = postData("/api/v1/kbs/" + kbId + "/retrieve",
                Map.of("query", "loopback provider", "topK", 3, "includeContext", true));
        assertThat(retrieved.path("chunks")).isNotEmpty();
        assertThat(retrieved.path("citations")).isNotEmpty();
        assertThat(retrieved.path("contextBlock").asText()).contains("<<<资料1>>>");
        assertThat(EMBEDDING_REQUESTS.get()).isGreaterThanOrEqualTo(2);
    }

    private JsonNode getData(String path) throws Exception {
        String body = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return unwrap(body);
    }

    private JsonNode postData(String path, Map<String, Object> body) throws Exception {
        String response = mockMvc.perform(post(path)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return unwrap(response);
    }

    private JsonNode unwrap(String body) throws IOException {
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

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/models", exchange -> respond(exchange, 200, "application/json",
                    "{\"data\":[{\"id\":\"" + MODEL + "\"}]}"));
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                CHAT_REQUESTS.incrementAndGet();
                respond(exchange, 200, "text/event-stream", """
                        data: {"model":"offline-loopback-model","choices":[{"delta":{"content":"本地 loopback 桩回答"}}]}

                        data: [DONE]

                        """);
            });
            server.createContext("/v1/embeddings", exchange -> {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                int count = request.path("input").isArray() ? request.path("input").size() : 0;
                StringBuilder data = new StringBuilder("{\"data\":[");
                for (int index = 0; index < count; index++) {
                    if (index > 0) {
                        data.append(',');
                    }
                    data.append("{\"index\":").append(index)
                            .append(",\"embedding\":[1.0,0.0,0.0,0.0]}");
                }
                data.append("]}");
                EMBEDDING_REQUESTS.incrementAndGet();
                respond(exchange, 200, "application/json", data.toString());
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动 loopback 测试桩", exception);
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
