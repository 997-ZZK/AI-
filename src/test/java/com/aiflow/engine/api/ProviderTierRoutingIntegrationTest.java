package com.aiflow.engine.api;

import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ProviderRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分级路由的装配测试：配置声明的 HTTP 端点 -> 路由 -> 降级 -> 状态接口，全链路走一遍。
 *
 * <p>不用真装模型也能验证「云端挂了自动退本地」：两个打桩服务分别扮演云端与本机端点，让云档案位按需失败。
 * 桩服务必须在**静态初始化**里起：{@code @DynamicPropertySource} 在 Spring 上下文刷新前就被求值，
 * 比 {@code @BeforeAll} 更早，把它写在 @BeforeAll 里会拿到 null。
 *
 * <p>方法带固定顺序，因为这个类的重点是「状态会变」：云端先健康、再失败、最后状态接口如实报出降级。
 * 靠运气过的测试不算测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProviderTierRoutingIntegrationTest {

    private static final AtomicBoolean CLOUD_HEALTHY = new AtomicBoolean(true);
    private static final HttpServer CLOUD_STUB = startCloudStub();
    private static final HttpServer LOCAL_STUB = startLocalStub();

    @Autowired
    private ProviderRouter router;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterAll
    static void stopStubs() {
        CLOUD_STUB.stop(0);
        LOCAL_STUB.stop(0);
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.provider.mode", () -> "auto");
        // 云端在前 = 云端加速；本地在后 = 兜底
        registry.add("aiflow.provider.order", () -> "cloud,local,mock");
        registry.add("aiflow.provider.endpoints.cloud.enabled", () -> "true");
        registry.add("aiflow.provider.endpoints.cloud.base-url", () -> baseUrl(CLOUD_STUB));
        registry.add("aiflow.provider.endpoints.cloud.model", () -> "gpt-x");
        registry.add("aiflow.provider.endpoints.cloud.capabilities", () -> "chat,vision");
        // 桩服务也跑在本机，但语义上是云档案位：档位显式声明，别让"猜地址"的默认规则决定矩阵
        registry.add("aiflow.provider.endpoints.cloud.tier", () -> "cloud");
        registry.add("aiflow.provider.endpoints.local.enabled", () -> "true");
        registry.add("aiflow.provider.endpoints.local.base-url", () -> baseUrl(LOCAL_STUB));
        registry.add("aiflow.provider.endpoints.local.model", () -> "qwen2.5:1.5b");
        registry.add("aiflow.provider.endpoints.local.capabilities", () -> "chat");
        // 一次失败即熔断，让「降级可见」在三个用例内完整走到；探活关掉，避免后台线程干扰失败计数
        registry.add("aiflow.provider.breaker.failure-threshold", () -> "1");
        registry.add("aiflow.provider.breaker.cooldown-ms", () -> "60000");
        registry.add("aiflow.provider.probe.enabled", () -> "false");
    }

    @Test
    @Order(1)
    void prefersCloudTierWhenItWorks() {
        CLOUD_HEALTHY.set(true);
        List<String> deltas = new ArrayList<>();

        ProviderRouter.ChatOutcome outcome = router.chat(Set.of(Capabilities.CHAT),
                new ChatRequest(null, List.of(ChatMessage.user("你好"))), deltas::add);

        assertThat(outcome.providerId()).isEqualTo("cloud");
        assertThat(outcome.response().content()).isEqualTo("云端回答");
        assertThat(deltas).containsExactly("云端回答");
        assertThat(outcome.failedAttempts()).isEmpty();
    }

    @Test
    @Order(2)
    void fallsBackToLocalTierWhenCloudFails() {
        CLOUD_HEALTHY.set(false);

        ProviderRouter.ChatOutcome outcome = router.chat(Set.of(Capabilities.CHAT),
                new ChatRequest(null, List.of(ChatMessage.user("你好"))), chunk -> { });

        assertThat(outcome.providerId()).isEqualTo("local");
        assertThat(outcome.response().content()).isEqualTo("本地模型回答");
        assertThat(outcome.failedAttempts()).hasSize(1);
        assertThat(outcome.failedAttempts().get(0).providerId()).isEqualTo("cloud");
        assertThat(outcome.failedAttempts().get(0).error()).contains("500");
    }

    @Test
    @Order(3)
    void capabilityReportSurfacesDegradationAfterCloudBreakage() throws Exception {
        JsonNode data = getData("/api/v1/capabilities");
        JsonNode chat = data.path("channels").get(0);

        assertThat(chat.path("channel").asText()).isEqualTo("chat");
        assertThat(chat.path("status").asText()).isEqualTo("degraded");
        assertThat(chat.path("activeProvider").asText()).isEqualTo("local");
        assertThat(chat.path("activeTier").asText()).isEqualTo("local");
        assertThat(data.path("notice").asText()).contains("已降级").contains("cloud");
        assertThat(data.path("level").asText()).as("本地对话可用 + 向量化仍是 mock 档 = L2").isEqualTo("L2");

        List<String> tiers = new ArrayList<>();
        for (JsonNode candidate : chat.path("candidates")) {
            tiers.add(candidate.path("tier").asText());
        }
        assertThat(tiers).containsExactly("cloud", "local", "mock");
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static HttpServer startCloudStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                if (CLOUD_HEALTHY.get()) {
                    respond(exchange, 200, "text/event-stream", """
                            data: {"model":"gpt-x","choices":[{"delta":{"content":"云端回答"}}]}

                            data: [DONE]

                            """);
                    return;
                }
                respond(exchange, 500, "application/json", "{\"error\":\"upstream busy\"}");
            });
            server.createContext("/v1/models", exchange -> respond(exchange, 200, "application/json", "{}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static HttpServer startLocalStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 200, "text/event-stream", """
                        data: {"model":"qwen2.5:1.5b","choices":[{"delta":{"content":"本地"}}]}

                        data: {"choices":[{"delta":{"content":"模型回答"}}]}

                        data: [DONE]

                        """);
            });
            server.createContext("/v1/models", exchange -> respond(exchange, 200, "application/json", "{}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode getData(String path) throws Exception {
        String body = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}