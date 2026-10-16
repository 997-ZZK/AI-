package com.aiflow.engine.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import com.aiflow.engine.provider.EndpointHealth;
import com.aiflow.engine.provider.ProviderCallException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Loopback protocol checks and deterministic faults; all private markers are synthetic. */
@Timeout(10)
class ChatErrorBoundaryTest {
    private static final String PROVIDER_ID = "chat-boundary";
    private static final String BODY_MARKER = "synthetic-upstream-body-private";
    private static final String CODE_MARKER = "synthetic-upstream-code-private";
    private static final String TYPE_MARKER = "synthetic-upstream-type-private";
    private static final String JSON_MARKER = "synthetic-malformed-json-private";
    private static final String EXCEPTION_MARKER = "synthetic-original-exception-private";
    private static final String CLOSE_MARKER = "synthetic-stream-close-private";
    private static final String REQUEST_MARKER = "synthetic-user-request-private";
    private static final String KEY_MARKER = "synthetic-configured-key-private";
    private static final String DELTA = "data: {\"choices\":[{\"delta\":{\"content\":\"safe answer\"}}]}\n\n";
    private static final String ERROR = "data: {\"error\":{\"message\":\"" + BODY_MARKER
            + "\",\"code\":\"" + CODE_MARKER + "\",\"type\":\"" + TYPE_MARKER + "\"}}\n\n";
    private static final String[] PRIVATE_MARKERS = {BODY_MARKER, CODE_MARKER, TYPE_MARKER, JSON_MARKER,
            EXCEPTION_MARKER, CLOSE_MARKER, REQUEST_MARKER, KEY_MARKER};

    private final EndpointHealth health = new EndpointHealth(PROVIDER_ID, true, 3, 60_000);
    private final Logger logger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleChatProvider.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final AtomicReference<ResponseAction> response = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private Level previousLevel;

    @BeforeEach
    void start() throws IOException {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        response.set(exchange -> reply(exchange, 200, "data: [DONE]\n"));
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            try {
                exchange.getRequestBody().readAllBytes();
                response.get().respond(exchange);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Error-body closure and intentionally truncated responses can close the client early.
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
        if (server != null) server.stop(0);
        if (executor != null) executor.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 429, 500})
    void httpFailuresOnlyExposeStatusAndCountOnce(int status) {
        response.set(exchange -> reply(exchange, status, "{\"error\":\"" + BODY_MARKER + "\"}"));
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER, baseUrl);

        ProviderCallException failure = failure(provider, new ArrayList<>());

        assertSafe(failure, "chat 调用失败 HTTP " + status);
        assertFailedOnce(failure.getMessage());
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void streamErrorDoesNotExposeMessageCodeOrType() {
        response.set(exchange -> reply(exchange, 200, ERROR));
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER, baseUrl);
        List<String> deltas = new ArrayList<>();

        ProviderCallException failure = failure(provider, deltas);

        assertSafe(failure, "chat 流返回错误");
        assertFailedOnce(failure.getMessage());
        assertThat(deltas).isEmpty();
    }

    @Test
    void malformedJsonIsIgnoredWithoutLoggingFragmentOrParserException() {
        response.set(exchange -> reply(exchange, 200, "data: {\"" + JSON_MARKER + "\":\n\n"
                + DELTA + "data: [DONE]\n"));
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER, baseUrl);
        List<String> deltas = new ArrayList<>();

        ChatResponse result = provider.chat(request(), deltas::add);

        assertThat(result.content()).isEqualTo("safe answer");
        assertThat(deltas).containsExactly("safe answer");
        assertThat(health.snapshot().state()).isEqualTo("up");
        assertThat(health.snapshot().consecutiveFailures()).isZero();
        assertThat(health.snapshot().lastError()).isNull();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).isEqualTo("provider=" + PROVIDER_ID + " 忽略无法解析的流片段");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertSafeLogs();
    }

    @Test
    void serializationFailureDropsOriginalExceptionBeforeSending() {
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException(EXCEPTION_MARKER) { };
            }
        };
        OpenAiCompatibleChatProvider provider = provider(failingMapper, KEY_MARKER, baseUrl);

        ProviderCallException failure = failure(provider, new ArrayList<>());

        assertSafe(failure, "请求体序列化失败");
        assertNotSent();
    }

    @Test
    void invalidHeaderFailureDoesNotExposeConfiguredValueBeforeSending() {
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER + "\r\nX-Synthetic: rejected", baseUrl);

        ProviderCallException failure = failure(provider, new ArrayList<>());

        assertSafe(failure, "chat 请求构造失败");
        assertNotSent();
    }

    @Test
    void truncatedFixedLengthStreamFailsAfterDeliveringFirstDelta() {
        CountDownLatch deltaDelivered = new CountDownLatch(1);
        AtomicBoolean serverObservedDelta = new AtomicBoolean();
        response.set(exchange -> {
            byte[] payload = DELTA.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length + 64L);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().flush();
            serverObservedDelta.set(deltaDelivered.await(3, TimeUnit.SECONDS));
            // The handler's finally closes the exchange without satisfying Content-Length.
        });
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER, baseUrl);
        List<String> deltas = new ArrayList<>();

        ProviderCallException failure = catchThrowableOfType(() -> provider.chat(request(), delta -> {
            deltas.add(delta);
            deltaDelivered.countDown();
        }), ProviderCallException.class);

        assertSafe(failure, "chat 流读取失败");
        assertFailedOnce(failure.getMessage());
        assertThat(deltas).containsExactly("safe answer");
        assertThat(serverObservedDelta).isTrue();
    }

    @Test
    void loopbackConnectionFailureDoesNotRetainTransportCauseOrUrl() throws IOException {
        int unusedPort;
        try (ServerSocket reservation = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = reservation.getLocalPort();
        }
        String unavailableUrl = "http://127.0.0.1:" + unusedPort + "/" + EXCEPTION_MARKER;
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER, unavailableUrl);

        ProviderCallException failure = failure(provider, new ArrayList<>());

        assertSafe(failure, "chat 调用失败: 网络/协议错误");
        assertFailedOnce(failure.getMessage());
        assertThat(stackTrace(failure)).doesNotContain(unavailableUrl);
        assertThat(requests.get()).isZero();
    }

    @Test
    void transportIOExceptionDoesNotRetainItsPrivateMessage() throws Exception {
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER, baseUrl);
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<Stream<String>>>any()))
                .thenThrow(new IOException(EXCEPTION_MARKER));
        ReflectionTestUtils.setField(provider, "httpClient", client);

        ProviderCallException failure = failure(provider, new ArrayList<>());

        assertSafe(failure, "chat 调用失败: 网络/协议错误");
        assertFailedOnce(failure.getMessage());
        assertThat(requests.get()).isZero();
    }

    @Test
    void streamReadIOExceptionDoesNotRetainItsPrivateMessageAfterDelta() throws Exception {
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER, baseUrl);
        Stream<String> broken = Stream.concat(Stream.of(DELTA.strip()), Stream.generate(() -> {
            throw new UncheckedIOException(new IOException(EXCEPTION_MARKER));
        }));
        useResponse(provider, 200, broken);
        List<String> deltas = new ArrayList<>();

        ProviderCallException failure = failure(provider, deltas);

        assertSafe(failure, "chat 流读取失败");
        assertFailedOnce(failure.getMessage());
        assertThat(deltas).containsExactly("safe answer");
    }

    @Test
    void httpErrorBodyIsClosedWithoutReadingEvenWhenCloseThrows() throws Exception {
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER, baseUrl);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        Stream<String> unread = Stream.<String>generate(() -> {
            reads.incrementAndGet();
            throw new AssertionError("HTTP error body must not be read");
        }).onClose(() -> {
            closes.incrementAndGet();
            throw new UncheckedIOException(new IOException(CLOSE_MARKER));
        });
        useResponse(provider, 500, unread);

        ProviderCallException failure = failure(provider, new ArrayList<>());

        assertSafe(failure, "chat 调用失败 HTTP 500");
        assertFailedOnce(failure.getMessage());
        assertThat(reads.get()).isZero();
        assertThat(closes.get()).isEqualTo(1);
    }

    @Test
    void streamErrorCloseFailureCannotBecomeSuppressedException() throws Exception {
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER, baseUrl);
        AtomicInteger closes = new AtomicInteger();
        Stream<String> error = Stream.of(ERROR.strip()).onClose(() -> {
            closes.incrementAndGet();
            throw new UncheckedIOException(new IOException(CLOSE_MARKER));
        });
        useResponse(provider, 200, error);

        ProviderCallException failure = failure(provider, new ArrayList<>());

        assertSafe(failure, "chat 流返回错误");
        assertFailedOnce(failure.getMessage());
        assertThat(closes.get()).isEqualTo(1);
    }

    @Test
    void closeFailureAfterSuccessfulReadRemainsOneSanitizedProviderFailure() throws Exception {
        OpenAiCompatibleChatProvider provider = provider(new ObjectMapper(), KEY_MARKER, baseUrl);
        AtomicInteger closes = new AtomicInteger();
        Stream<String> complete = Stream.of(DELTA.strip(), "data: [DONE]").onClose(() -> {
            closes.incrementAndGet();
            throw new UncheckedIOException(new IOException(CLOSE_MARKER));
        });
        useResponse(provider, 200, complete);
        List<String> deltas = new ArrayList<>();

        ProviderCallException failure = failure(provider, deltas);

        assertSafe(failure, "chat 流读取失败");
        assertFailedOnce(failure.getMessage());
        assertThat(deltas).containsExactly("safe answer");
        assertThat(closes.get()).isEqualTo(1);
    }

    private OpenAiCompatibleChatProvider provider(ObjectMapper mapper, String apiKey, String url) {
        ProviderProperties.Endpoint endpoint = new ProviderProperties.Endpoint(url, "synthetic-model", apiKey,
                Set.of("chat"), true, 3_000, "", "", 0);
        return new OpenAiCompatibleChatProvider(PROVIDER_ID, endpoint, health, mapper);
    }

    private static ChatRequest request() {
        return new ChatRequest(null, List.of(ChatMessage.user(REQUEST_MARKER)));
    }

    private static ProviderCallException failure(OpenAiCompatibleChatProvider provider, List<String> deltas) {
        return catchThrowableOfType(() -> provider.chat(request(), deltas::add), ProviderCallException.class);
    }

    private void assertSafe(ProviderCallException failure, String message) {
        assertThat(failure).isNotNull();
        assertThat(failure.getProviderId()).isEqualTo(PROVIDER_ID);
        assertThat(failure.getMessage()).isEqualTo(message).doesNotContain(PRIVATE_MARKERS);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(stackTrace(failure)).doesNotContain(PRIVATE_MARKERS);
        assertSafeLogs();
    }

    private void assertSafeLogs() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_MARKERS);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private void assertFailedOnce(String message) {
        assertThat(health.snapshot().state()).isEqualTo("down");
        assertThat(health.snapshot().lastError()).isEqualTo(message).doesNotContain(PRIVATE_MARKERS);
        assertThat(health.snapshot().consecutiveFailures()).isEqualTo(1);
    }

    private void assertNotSent() {
        assertThat(requests.get()).isZero();
        assertThat(health.snapshot().state()).isEqualTo("up");
        assertThat(health.snapshot().consecutiveFailures()).isZero();
        assertThat(health.snapshot().lastError()).isNull();
    }

    private static String stackTrace(Throwable failure) {
        StringWriter printed = new StringWriter();
        failure.printStackTrace(new PrintWriter(printed));
        return printed.toString();
    }

    @SuppressWarnings("unchecked")
    private static void useResponse(OpenAiCompatibleChatProvider provider, int status, Stream<String> body) throws Exception {
        HttpResponse<Stream<String>> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<Stream<String>>>any()))
                .thenReturn(response);
        ReflectionTestUtils.setField(provider, "httpClient", client);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
    }

    @FunctionalInterface
    private interface ResponseAction {
        void respond(HttpExchange exchange) throws IOException, InterruptedException;
    }
}
