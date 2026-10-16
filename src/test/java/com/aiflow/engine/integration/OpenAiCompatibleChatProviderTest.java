package com.aiflow.engine.integration;

import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.node.RunCanceledException;
import com.aiflow.engine.provider.ChatImage;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import com.aiflow.engine.provider.EndpointHealth;
import com.aiflow.engine.provider.ProviderCallException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI 兼容 chat provider 的协议测试，打桩用 JDK 自带的 HttpServer（不引第三方 mock 框架）。
 *
 * <p>覆盖三件真踩到就会白屏的事：SSE 分片怎么拼、错误响应怎么变成可读异常、请求体里的 model 是不是被覆盖了。
 */
class OpenAiCompatibleChatProviderTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicReference<StubResponse> chatResponse = new AtomicReference<>();
    private static final AtomicReference<ChatHandler> customChatHandler = new AtomicReference<>();
    private static final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ChatHandler custom = customChatHandler.get();
            if (custom != null) {
                custom.handle(exchange);
                return;
            }
            StubResponse response = chatResponse.get();
            respond(exchange, response.status(), response.body(), response.contentType());
        });
        server.createContext("/v1/models", exchange -> respond(exchange, 200, "{\"data\":[]}", "application/json"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        lastRequestBody.set(null);
        customChatHandler.set(null);
        chatResponse.set(new StubResponse(200, "", "text/event-stream"));
    }

    @Test
    void streamsDeltasAndReadsUsage() {
        chatResponse.set(new StubResponse(200, """
                data: {"model":"qwen2.5:1.5b","choices":[{"delta":{"content":"你好"}}]}

                data: {"choices":[{"delta":{"content":"，世界"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7}}

                data: [DONE]

                """, "text/event-stream"));
        OpenAiCompatibleChatProvider provider = provider("local", new EndpointHealth("local", true, 3, 1_000));
        List<String> deltas = new ArrayList<>();

        ChatResponse response = provider.chat(new ChatRequest(null, List.of(ChatMessage.user("你好"))), deltas::add);

        assertThat(response.content()).isEqualTo("你好，世界");
        assertThat(response.model()).isEqualTo("qwen2.5:1.5b");
        assertThat(response.promptTokens()).isEqualTo(11);
        assertThat(response.completionTokens()).isEqualTo(7);
        assertThat(deltas).containsExactly("你好", "，世界");
    }

    @Test
    void convertsHttpErrorIntoProviderFailureAndMarksEndpointDown() {
        String marker = "synthetic-prompt-and-key-marker";
        chatResponse.set(new StubResponse(500, "{\"error\":\"" + marker + "\"}", "application/json"));
        EndpointHealth health = new EndpointHealth("cloud", true, 3, 1_000);
        OpenAiCompatibleChatProvider provider = provider("cloud", health);

        assertThatThrownBy(() -> provider.chat(new ChatRequest(null, List.of(ChatMessage.user("hi"))), chunk -> { }))
                .isInstanceOf(ProviderCallException.class)
                .hasMessage("chat 调用失败 HTTP 500")
                .satisfies(error -> {
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getSuppressed()).isEmpty();
                    assertThat(error.toString()).doesNotContain(marker);
                });

        assertThat(health.available()).as("失败写回健康状态：档位转为不可用，路由会退到下一档").isFalse();
        assertThat(health.snapshot().state()).isEqualTo("down");
        assertThat(health.snapshot().lastError()).isEqualTo("chat 调用失败 HTTP 500");
        assertThat(health.snapshot().lastError()).doesNotContain(marker);
    }

    @Test
    void doesNotPropagateErrorObjectFromStream() {
        String marker = "synthetic-prompt-and-key-marker";
        chatResponse.set(new StubResponse(200,
                "data: {\"error\":{\"code\":\"bad\",\"type\":\"upstream\",\"message\":\"" + marker + "\"}}\n",
                "text/event-stream"));
        EndpointHealth health = new EndpointHealth("cloud", true, 3, 1_000);
        OpenAiCompatibleChatProvider provider = provider("cloud", health);

        assertThatThrownBy(() -> provider.chat(new ChatRequest(null, List.of(ChatMessage.user("hi"))), chunk -> { }))
                .isInstanceOf(ProviderCallException.class)
                .hasMessage("chat 流返回错误")
                .satisfies(error -> {
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getSuppressed()).isEmpty();
                    assertThat(error.toString()).doesNotContain(marker);
                });
        assertThat(health.snapshot().lastError()).isEqualTo("chat 流返回错误");
        assertThat(health.snapshot().lastError()).doesNotContain(marker);
    }

    @Test
    void requestOverridesConfiguredModel() {
        chatResponse.set(new StubResponse(200, "data: [DONE]\n", "text/event-stream"));
        OpenAiCompatibleChatProvider provider = provider("local", new EndpointHealth("local", true, 3, 1_000));

        provider.chat(new ChatRequest("自定义模型", List.of(ChatMessage.user("hi"))), chunk -> { });

        assertThat(lastRequestBody.get()).contains("\"model\":\"自定义模型\"");
    }

    @Test
    void usesConfiguredModelAndStreamingWhenRequestDoesNotOverride() {
        chatResponse.set(new StubResponse(200, "data: [DONE]\n", "text/event-stream"));
        OpenAiCompatibleChatProvider provider = provider("local", new EndpointHealth("local", true, 3, 1_000));

        provider.chat(new ChatRequest(null, List.of(ChatMessage.user("hi"))), chunk -> { });

        assertThat(lastRequestBody.get()).contains("\"model\":\"qwen2.5:1.5b\"");
        assertThat(lastRequestBody.get()).contains("\"stream\":true");
    }

    @Test
    void cancellationOnInterruptKeepsEndpointHealthy() throws Exception {
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);
        OpenAiCompatibleChatProvider provider = provider("local", health);
        AtomicReference<Throwable> captured = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                provider.chat(new ChatRequest(null, List.of(ChatMessage.user("hi"))), chunk -> { });
            } catch (Throwable error) {
                captured.set(error);
            }
        });
        thread.start();
        thread.interrupt();
        thread.join(5_000);

        assertThat(thread.isAlive()).isFalse();
        assertThat(captured.get())
                .as("取消不是 provider 故障：必须抛 RunCanceledException，不能被降级逻辑当成换档信号")
                .isInstanceOf(RunCanceledException.class);
        assertThat(health.snapshot().state()).isEqualTo("up");
    }

    @Test
    @Timeout(5)
    void responseHeadersWithoutBodyAreReportedAsStreamTimeout() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        customChatHandler.set(exchange -> {
            try {
                sendStreamingHeaders(exchange);
                headersSent.countDown();
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        EndpointHealth health = new EndpointHealth("cloud", true, 3, 1_000);
        OpenAiCompatibleChatProvider provider = provider("cloud", health, "", 180);
        AtomicReference<Throwable> captured = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                provider.chat(new ChatRequest(null, List.of(ChatMessage.user("stall"))), chunk -> { });
            } catch (Throwable error) {
                captured.set(error);
            }
        });

        worker.start();
        try {
            assertThat(headersSent.await(2, TimeUnit.SECONDS)).isTrue();
            worker.join(2_000);
        } finally {
            release.countDown();
            worker.join(2_000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(captured.get()).isInstanceOf(ProviderCallException.class)
                .hasMessage("chat 流读取超时")
                .hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
        assertThat(health.snapshot().state()).isEqualTo("down");
        assertThat(health.snapshot().lastError()).isEqualTo("chat 流读取超时");
    }

    @Test
    @Timeout(5)
    void continuousSmallSsePiecesCannotOutrunTheTotalDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        customChatHandler.set(exchange -> {
            try {
                sendStreamingHeaders(exchange);
                OutputStream out = exchange.getResponseBody();
                for (int i = 0; i < 20 && !release.await(45, TimeUnit.MILLISECONDS); i++) {
                    out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Client-side timeout closes this response stream; the test asserts the provider result.
            } finally {
                exchange.close();
            }
        });
        EndpointHealth health = new EndpointHealth("cloud", true, 3, 1_000);
        OpenAiCompatibleChatProvider provider = provider("cloud", health, "", 220);

        try {
            assertThatThrownBy(() -> provider.chat(new ChatRequest(null, List.of(ChatMessage.user("slow"))),
                    chunk -> { })).isInstanceOf(ProviderCallException.class)
                    .hasMessage("chat 流读取超时")
                    .hasNoCause();
        } finally {
            release.countDown();
        }
        assertThat(health.snapshot().state()).isEqualTo("down");
    }

    @Test
    @Timeout(5)
    void doneBeforeDeadlineSucceedsAndItsTimerDoesNotAffectTheNextCall() {
        chatResponse.set(new StubResponse(200, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n"
                + "data: [DONE]\n", "text/event-stream"));
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);
        OpenAiCompatibleChatProvider provider = provider("local", health, "", 300);

        assertThat(provider.chat(new ChatRequest(null, List.of(ChatMessage.user("one"))), chunk -> { }).content())
                .isEqualTo("ok");
        assertThat(provider.chat(new ChatRequest(null, List.of(ChatMessage.user("two"))), chunk -> { }).content())
                .isEqualTo("ok");
        assertThat(health.snapshot().state()).isEqualTo("up");
    }

    @Test
    @Timeout(5)
    void interruptAfterFirstDeltaIsCancellationAndKeepsEndpointHealthy() throws Exception {
        CountDownLatch firstDelta = new CountDownLatch(1);
        CountDownLatch deltaDelivered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        customChatHandler.set(exchange -> {
            try {
                sendStreamingHeaders(exchange);
                OutputStream out = exchange.getResponseBody();
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"prefix\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                firstDelta.countDown();
                release.await(3, TimeUnit.SECONDS);
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The provider closes the stream after cancellation.
            } finally {
                exchange.close();
            }
        });
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);
        OpenAiCompatibleChatProvider provider = provider("local", health, "", 2_000);
        AtomicReference<Throwable> captured = new AtomicReference<>();
        AtomicReference<List<String>> deltas = new AtomicReference<>(new ArrayList<>());
        Thread worker = new Thread(() -> {
            try {
                provider.chat(new ChatRequest(null, List.of(ChatMessage.user("cancel"))),
                        delta -> {
                            deltas.get().add(delta);
                            deltaDelivered.countDown();
                        });
            } catch (Throwable error) {
                captured.set(error);
            }
        });

        worker.start();
        assertThat(firstDelta.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(deltaDelivered.await(2, TimeUnit.SECONDS)).isTrue();
        worker.interrupt();
        try {
            worker.join(2_000);
        } finally {
            release.countDown();
        }

        assertThat(worker.isAlive()).isFalse();
        assertThat(captured.get()).isInstanceOf(RunCanceledException.class);
        assertThat(deltas.get()).containsExactly("prefix");
        assertThat(worker.isInterrupted()).isTrue();
        assertThat(health.snapshot().state()).isEqualTo("up");
        assertThat(health.snapshot().lastError()).isNull();
    }

    @Test
    void serializesImagesAsOpenAiContentParts() throws Exception {
        chatResponse.set(new StubResponse(200, "data: [DONE]\n", "text/event-stream"));
        OpenAiCompatibleChatProvider provider = provider("local", new EndpointHealth("local", true, 3, 1_000));

        provider.chat(new ChatRequest(null, List.of(ChatMessage.user("图里有什么",
                List.of(ChatImage.ofBytes("image/png", new byte[]{1, 2, 3, 4}))))), chunk -> { });

        JsonNode content = objectMapper.readTree(lastRequestBody.get()).path("messages").get(0).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("图里有什么");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText()).startsWith("data:image/png;base64,");
    }

    @Test
    void keepsPlainTextContentAsString() throws Exception {
        chatResponse.set(new StubResponse(200, "data: [DONE]\n", "text/event-stream"));
        OpenAiCompatibleChatProvider provider = provider("local", new EndpointHealth("local", true, 3, 1_000));

        provider.chat(new ChatRequest(null, List.of(ChatMessage.user("你好"))), chunk -> { });

        JsonNode content = objectMapper.readTree(lastRequestBody.get()).path("messages").get(0).path("content");
        assertThat(content.isTextual())
                .as("纯文本必须保持字符串：数组形态会踩到支持不全的兼容网关")
                .isTrue();
    }

    @Test
    void mergesVendorExtraBody() throws Exception {
        chatResponse.set(new StubResponse(200, "data: [DONE]\n", "text/event-stream"));
        OpenAiCompatibleChatProvider provider = provider("ark", new EndpointHealth("ark", true, 3, 1_000),
                "{\"thinking\":{\"type\":\"disabled\"}}");

        provider.chat(new ChatRequest(null, List.of(ChatMessage.user("你好"))), chunk -> { });

        JsonNode body = objectMapper.readTree(lastRequestBody.get());
        assertThat(body.path("thinking").path("type").asText())
                .as("厂商私有字段必须原样进请求体：推理链默认开着，一个简单提问能跑满 100s 还没答完")
                .isEqualTo("disabled");
        assertThat(body.path("model").asText()).isEqualTo("qwen2.5:1.5b");
        assertThat(body.path("stream").asBoolean()).isTrue();
    }

    @Test
    void rejectsInvalidExtraBodyBeforeSendingRequest() {
        String marker = "synthetic-private-vendor-config";
        EndpointHealth health = new EndpointHealth("cloud", true, 3, 1_000);

        assertThatThrownBy(() -> provider("cloud", health, "{\"vendor\":\"" + marker + "\",broken}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("extra-body 不是合法 JSON 对象")
                .hasNoCause()
                .satisfies(error -> {
                    assertThat(error.getSuppressed()).isEmpty();
                    assertThat(error.toString()).doesNotContain(marker);
                });

        assertThat(lastRequestBody.get()).isNull();
        assertThat(health.snapshot().state()).isEqualTo("up");
    }

    @Test
    void preservesNestedNullAndScalarVendorFieldsInRequest() throws Exception {
        chatResponse.set(new StubResponse(200, "data: [DONE]\n", "text/event-stream"));
        OpenAiCompatibleChatProvider provider = provider("cloud", new EndpointHealth("cloud", true, 3, 1_000),
                "{\"thinking\":{\"type\":null},\"enable_thinking\":false,\"temperature\":0.25,\"stop\":[\"END\"]}");

        provider.chat(new ChatRequest(null, List.of(ChatMessage.user("synthetic query"))), chunk -> { });

        JsonNode body = objectMapper.readTree(lastRequestBody.get());
        assertThat(body.path("thinking").path("type").isNull()).isTrue();
        assertThat(body.path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.25);
        assertThat(body.path("stop").get(0).asText()).isEqualTo("END");
        assertThat(body.path("model").asText()).isEqualTo("qwen2.5:1.5b");
        assertThat(body.path("stream").asBoolean()).isTrue();
    }

    private OpenAiCompatibleChatProvider provider(String id, EndpointHealth health) {
        return provider(id, health, "");
    }

    private OpenAiCompatibleChatProvider provider(String id, EndpointHealth health, String extraBody) {
        return provider(id, health, extraBody, 5_000);
    }

    private OpenAiCompatibleChatProvider provider(String id, EndpointHealth health, String extraBody, long timeoutMs) {
        ProviderProperties.Endpoint endpoint = new ProviderProperties.Endpoint(baseUrl, "qwen2.5:1.5b", "",
                Set.of("chat"), true, timeoutMs, "", extraBody, 0);
        return new OpenAiCompatibleChatProvider(id, endpoint, health, objectMapper);
    }

    private static void sendStreamingHeaders(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body,
                                String contentType) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private record StubResponse(int status, String body, String contentType) {
    }

    @FunctionalInterface
    private interface ChatHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
