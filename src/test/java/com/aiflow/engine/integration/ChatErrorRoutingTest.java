package com.aiflow.engine.integration;

import com.aiflow.engine.budget.InMemoryTokenUsageStore;
import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.config.BudgetProperties;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.config.SafetyProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.node.builtin.LlmNodeExecutor;
import com.aiflow.engine.provider.AllProvidersFailedException;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatProvider;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import com.aiflow.engine.provider.EndpointHealth;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.safety.ContentSafety;
import com.aiflow.engine.safety.StreamingSafetyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatErrorRoutingTest {

    private static final String MARKER = "synthetic-upstream-prompt-and-key-marker";
    private static final String FALLBACK = "synthetic fallback answer";
    private static final String FALLBACK_ERROR = "synthetic fallback unavailable";
    private static final Set<String> CHAT = Set.of(Capabilities.CHAT);
    private static final AtomicReference<StubResponse> RESPONSE = new AtomicReference<>();

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            StubResponse response = RESPONSE.get();
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void httpErrorBeforeOutputFallsBackWithoutLeakingIntoAttemptsOrNodeTrace() {
        RESPONSE.set(new StubResponse(503, "{\"error\":{\"message\":\"" + MARKER + "\"}}",
                "application/json"));

        assertFallbackAndNodeTrace("chat 调用失败 HTTP 503");
    }

    @Test
    void streamErrorBeforeOutputFallsBackWithoutLeakingIntoAttemptsOrNodeTrace() {
        RESPONSE.set(new StubResponse(200, streamError(), "text/event-stream"));

        assertFallbackAndNodeTrace("chat 流返回错误");
    }

    @Test
    void streamErrorAfterOutputStopsRoutingAndKeepsFailureDetailsSafe() {
        RESPONSE.set(new StubResponse(200, """
                data: {"choices":[{"delta":{"content":"visible prefix"}}]}

                """ + streamError(), "text/event-stream"));
        Fixture fixture = new Fixture(false);
        List<String> deltas = new ArrayList<>();

        AllProvidersFailedException failure = assertThrows(AllProvidersFailedException.class,
                () -> fixture.router.chat(CHAT, request(), deltas::add));

        assertThat(deltas).containsExactly("visible prefix");
        assertThat(fixture.fallbackCalls).hasValue(0);
        assertThat(failure.isPartialOutputDelivered()).isTrue();
        assertThat(failure.getAttempts()).containsExactly(
                new AllProvidersFailedException.FailedAttempt("cloud", "chat 流返回错误"));
        assertThat(failure.getMessage()).contains("chat 流返回错误").doesNotContain(MARKER);
        assertSafeException(failure);
        assertHealthFailure(fixture.health, "chat 流返回错误");
    }

    @Test
    void allCandidatesFailWithoutLeakingIntoFinalExceptionOrFailedNode() {
        RESPONSE.set(new StubResponse(502, MARKER, "text/plain"));
        Fixture fixture = new Fixture(true);
        List<String> deltas = new ArrayList<>();

        AllProvidersFailedException failure = assertThrows(AllProvidersFailedException.class,
                () -> fixture.router.chat(CHAT, request(), deltas::add));

        assertThat(deltas).isEmpty();
        assertThat(fixture.fallbackCalls).hasValue(1);
        assertThat(failure.isPartialOutputDelivered()).isFalse();
        assertThat(failure.getAttempts()).containsExactly(
                new AllProvidersFailedException.FailedAttempt("cloud", "chat 调用失败 HTTP 502"),
                new AllProvidersFailedException.FailedAttempt("mock", FALLBACK_ERROR));
        assertThat(failure.getMessage()).contains("chat 调用失败 HTTP 502", FALLBACK_ERROR)
                .doesNotContain(MARKER);
        assertSafeException(failure);
        assertHealthFailure(fixture.health, "chat 调用失败 HTTP 502");

        Fixture nodeFixture = new Fixture(true);
        NodeResult result = executeNode(nodeFixture.router);
        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).isEqualTo(failure.getMessage()).doesNotContain(MARKER);
        assertThat(nodeFixture.fallbackCalls).hasValue(1);
        assertHealthFailure(nodeFixture.health, "chat 调用失败 HTTP 502");
    }

    private static void assertFallbackAndNodeTrace(String expectedError) {
        Fixture fixture = new Fixture(false);
        List<String> deltas = new ArrayList<>();

        ProviderRouter.ChatOutcome outcome = fixture.router.chat(CHAT, request(), deltas::add);

        assertThat(outcome.providerId()).isEqualTo("mock");
        assertThat(outcome.response().content()).isEqualTo(FALLBACK);
        assertThat(outcome.failedAttempts()).containsExactly(
                new AllProvidersFailedException.FailedAttempt("cloud", expectedError));
        assertThat(outcome.failedAttempts().toString()).doesNotContain(MARKER);
        assertThat(outcome.skippedProviders()).isEmpty();
        assertThat(deltas).containsExactly(FALLBACK);
        assertThat(fixture.fallbackCalls).hasValue(1);
        assertHealthFailure(fixture.health, expectedError);

        // 重新创建健康端点，验证实际节点输出中的 DegradationTrace，而非重建预期字段。
        Fixture nodeFixture = new Fixture(false);
        NodeResult result = executeNode(nodeFixture.router);
        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(result.error()).isNull();
        assertThat(result.output()).containsEntry("reply", FALLBACK)
                .containsEntry("provider", "mock")
                .containsEntry("degradedFrom", List.of("cloud"))
                .containsEntry("degradedReasons", List.of(expectedError));
        assertThat(result.output().toString()).doesNotContain(MARKER);
        assertThat(nodeFixture.fallbackCalls).hasValue(1);
        assertHealthFailure(nodeFixture.health, expectedError);
    }

    private static NodeResult executeNode(ProviderRouter router) {
        BudgetProperties budgetProperties = BudgetProperties.defaults();
        SafetyProperties safetyProperties = SafetyProperties.defaults();
        ContentSafety safety = new ContentSafety(safetyProperties, new DefaultResourceLoader());
        LlmNodeExecutor executor = new LlmNodeExecutor(router, safety, safetyProperties,
                new TokenBudgetService(new InMemoryTokenUsageStore(), budgetProperties), budgetProperties,
                new StreamingSafetyGuard(safety, safetyProperties));
        NodeDef node = new NodeDef("llm", "llm.chat", "llm", Map.of("prompt", "${input}"), CHAT, null, null);
        NodeContext context = new NodeContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), node,
                new LinkedHashMap<>(Map.of("input", "synthetic request")),
                new InMemoryRunEventBus(EngineProperties.defaults()), () -> false);
        return executor.execute(context);
    }

    private static void assertHealthFailure(EndpointHealth health, String expectedError) {
        assertThat(health.snapshot().state()).isEqualTo("down");
        assertThat(health.snapshot().consecutiveFailures()).isEqualTo(1);
        assertThat(health.snapshot().lastError()).isEqualTo(expectedError).doesNotContain(MARKER);
    }

    private static void assertSafeException(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(MARKER);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private static ChatRequest request() {
        return new ChatRequest(null, List.of(ChatMessage.user("synthetic request")));
    }

    private static String streamError() {
        return "data: {\"error\":{\"code\":\"" + MARKER + "\",\"message\":\"" + MARKER + "\"}}\n\n";
    }

    private static final class Fixture {
        private final EndpointHealth health = new EndpointHealth("cloud", true, 3, 1_000);
        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private final ProviderRouter router;

        private Fixture(boolean failFallback) {
            ProviderProperties.Endpoint endpoint = new ProviderProperties.Endpoint(baseUrl, "synthetic-model", "",
                    CHAT, true, 5_000, "", "", 0);
            ChatProvider cloud = new OpenAiCompatibleChatProvider("cloud", endpoint, health, new ObjectMapper());
            ChatProvider fallback = new ChatProvider() {
                @Override
                public String id() { return "mock"; }

                @Override
                public Set<String> capabilities() { return CHAT; }

                @Override
                public ChatResponse chat(ChatRequest request, Consumer<String> sink) {
                    fallbackCalls.incrementAndGet();
                    if (failFallback) {
                        throw new IllegalStateException(FALLBACK_ERROR);
                    }
                    sink.accept(FALLBACK);
                    return new ChatResponse(FALLBACK, "mock-model", 1, 1);
                }
            };
            ProviderProperties defaults = ProviderProperties.defaults();
            ProviderProperties properties = new ProviderProperties("auto", new String[]{"cloud", "mock"}, List.of(),
                    defaults.mock(), Map.of(), defaults.probe(), defaults.breaker());
            router = new ProviderRouter(List.of(cloud, fallback), properties);
        }
    }

    private record StubResponse(int status, String body, String contentType) { }
}
