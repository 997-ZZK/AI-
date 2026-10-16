package com.aiflow.engine.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.provider.EndpointHealth;
import com.aiflow.engine.retrieval.RetrievalException;
import com.aiflow.engine.retrieval.TieredEmbeddingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingErrorBoundaryTest {

    private static final String MARKER = "synthetic-upstream-embedding-prompt-and-key-marker";
    private static final String MODEL = "synthetic-embedding-model";
    private static final String KEY = "synthetic-embedding-key";
    private static final String SUCCESS = "{\"data\":[{\"embedding\":[0.1,0.2,0.3],\"index\":0}]}";
    private static final List<String> TEXTS = List.of("synthetic request");
    private static final Map<String, StubResponse> RESPONSES = new ConcurrentHashMap<>();
    private static final List<RecordedRequest> REQUESTS = new CopyOnWriteArrayList<>();

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            REQUESTS.add(new RecordedRequest(exchange.getRequestMethod(), path, body,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            StubResponse response = RESPONSES.getOrDefault(path, new StubResponse(500, MARKER));
            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void resetServer() {
        RESPONSES.clear();
        REQUESTS.clear();
        RESPONSES.put("/v1/embeddings", new StubResponse(200, SUCCESS));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 429, 500, 302})
    void reportsOnlyStatusForNonSuccessHttpResponses(int status) {
        // 302 不带 Location，验证实际收到的 3xx 也走状态处理器。
        RESPONSES.put("/v1/embeddings", new StubResponse(status,
                "{\"error\":{\"message\":\"" + MARKER + "\"}}"));

        RetrievalException failure = assertThrows(RetrievalException.class,
                () -> provider("/v1", KEY).embedAll(TEXTS));

        assertThat(failure).hasMessage("embedding 服务调用失败 HTTP " + status);
        assertSanitized(failure);
        assertThat(REQUESTS).hasSize(1);
    }

    @Test
    void sanitizesJsonConversionFailureContainingUpstreamText() {
        RESPONSES.put("/v1/embeddings", new StubResponse(200,
                "{\"data\":[{\"embedding\":[\"" + MARKER + "\"],\"index\":0}]}"));

        RetrievalException failure = assertThrows(RetrievalException.class,
                () -> provider("/v1", KEY).embedAll(TEXTS));

        assertThat(failure).hasMessage("embedding 服务调用失败: 网络/协议错误");
        assertSanitized(failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"data\":[{}]}", "{\"data\":[{\"embedding\":[]}]}", "{\"data\":[null]}"})
    void preservesMissingAndEmptyVectorValidation(String body) {
        RESPONSES.put("/v1/embeddings", new StubResponse(200, body));

        RetrievalException failure = assertThrows(RetrievalException.class,
                () -> provider("/v1", KEY).embedAll(TEXTS));

        assertThat(failure).hasMessage("embedding 服务返回空向量");
        assertSanitized(failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"data\":null}", "{\"data\":[]}"})
    void preservesMissingResponseAndWrongCountValidation(String body) {
        RESPONSES.put("/v1/embeddings", new StubResponse(200, body));

        RetrievalException failure = assertThrows(RetrievalException.class,
                () -> provider("/v1", KEY).embedAll(TEXTS));

        assertThat(failure).hasMessage("embedding 服务返回条数与请求不一致: 0 != 1");
        assertSanitized(failure);
    }

    @Test
    void keepsBatchVectorsUtf8RequestModelAndBearerHeader() throws IOException {
        RESPONSES.put("/v1/embeddings", new StubResponse(200, """
                {"data":[{"embedding":[0.1,null,-0.3],"index":0},
                         {"embedding":[0.4,0.5,0.6],"index":1}],"model":"synthetic-response-model"}
                """));
        HttpEmbeddingProvider provider = provider("/v1", KEY);

        List<float[]> vectors = provider.embedAll(List.of("你好，世界", "synthetic second input"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0f, -0.3f);
        assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f, 0.6f);
        assertThat(provider.id()).isEqualTo("http-embedding:" + MODEL);
        assertThat(provider.dimensions()).isEqualTo(3);
        assertThat(REQUESTS).hasSize(1);
        RecordedRequest request = REQUESTS.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/embeddings");
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(request.authorization()).isEqualTo("Bearer " + KEY);
        JsonNode body = new ObjectMapper().readTree(request.body());
        assertThat(body.path("model").asText()).isEqualTo(MODEL);
        assertThat(body.path("input").get(0).asText()).isEqualTo("你好，世界");
        assertThat(body.path("input").get(1).asText()).isEqualTo("synthetic second input");
    }

    @Test
    void omitsAuthorizationWhenNoKeyIsConfigured() {
        assertThat(provider("/v1", "").embedAll(TEXTS)).hasSize(1);

        assertThat(REQUESTS).hasSize(1);
        assertThat(REQUESTS.getFirst().authorization()).isNull();
    }

    @Test
    void returnsEmptyWithoutMakingAnHttpRequest() {
        RESPONSES.put("/v1/embeddings", new StubResponse(500, MARKER));

        assertThat(provider("/v1", KEY).embedAll(List.of())).isEmpty();

        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void removesNetworkExceptionMessageCauseAndSuppressedData() {
        ResourceAccessException source = new ResourceAccessException(MARKER, new IOException(MARKER));
        source.addSuppressed(new IllegalStateException(MARKER));

        assertClientFailureSanitized(source, "embedding 服务调用失败: 网络/协议错误");
    }

    @Test
    void removesDirectMessageConversionExceptionAndItsCause() {
        HttpMessageConversionException source = new HttpMessageConversionException(MARKER,
                new IllegalStateException(MARKER));
        source.addSuppressed(new IllegalStateException(MARKER));

        assertClientFailureSanitized(source, "embedding 服务调用失败: 网络/协议错误");
    }

    @Test
    void sanitizesIllegalAuthorizationValueWithoutContactingServer() {
        RetrievalException failure = assertThrows(RetrievalException.class,
                () -> provider("/v1", MARKER + "\nsynthetic-invalid-header").embedAll(TEXTS));

        assertThat(failure).hasMessage("embedding 请求构造失败");
        assertSanitized(failure);
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void realHttpProviderFallsBackWithSanitizedHealthStatusAndWarning() {
        RESPONSES.put("/cloud/v1/embeddings", new StubResponse(500, MARKER));
        RESPONSES.put("/local/v1/embeddings", new StubResponse(200, SUCCESS));
        EndpointHealth cloud = health("cloud");
        EndpointHealth local = health("local");
        TieredEmbeddingProvider chain = chain(cloud, local);
        Logger logger = (Logger) LoggerFactory.getLogger(TieredEmbeddingProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<float[]> vectors = chain.embedAll(TEXTS);

            assertThat(vectors).hasSize(1);
            assertThat(vectors.getFirst()).containsExactly(0.1f, 0.2f, 0.3f);
            assertThat(chain.id()).isEqualTo("http-embedding:" + MODEL);
            assertThat(chain.dimensions()).isEqualTo(3);
            assertThat(chain.healthy()).isTrue();
            assertHealthFailure(cloud, "embedding 服务调用失败 HTTP 500");
            assertThat(local.snapshot().state()).isEqualTo("up");
            assertThat(local.snapshot().consecutiveFailures()).isZero();
            assertThat(local.snapshot().lastError()).isNull();
            assertThat(chain.status().getFirst()).containsEntry("lastError", "embedding 服务调用失败 HTTP 500");
            assertThat(chain.status().toString()).doesNotContain(MARKER);
            assertWarningsSanitized(appender, 1);
            assertThat(REQUESTS).extracting(RecordedRequest::path)
                    .containsExactly("/cloud/v1/embeddings", "/local/v1/embeddings");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void allRealHttpTiersFailWithoutLeakingIntoHealthStatusLogsOrFinalFailure() {
        RESPONSES.put("/cloud/v1/embeddings", new StubResponse(429, MARKER));
        RESPONSES.put("/local/v1/embeddings", new StubResponse(502, MARKER));
        EndpointHealth cloud = health("cloud");
        EndpointHealth local = health("local");
        TieredEmbeddingProvider chain = chain(cloud, local);
        Logger logger = (Logger) LoggerFactory.getLogger(TieredEmbeddingProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RetrievalException failure = assertThrows(RetrievalException.class, () -> chain.embedAll(TEXTS));

            assertThat(failure).hasMessage("所有 embedding 档位都失败: cloud=embedding 服务调用失败 HTTP 429; "
                    + "local=embedding 服务调用失败 HTTP 502");
            assertSanitized(failure);
            assertHealthFailure(cloud, "embedding 服务调用失败 HTTP 429");
            assertHealthFailure(local, "embedding 服务调用失败 HTTP 502");
            assertThat(chain.healthy()).isFalse();
            assertThat(chain.status()).hasSize(2);
            assertThat(chain.status().toString()).doesNotContain(MARKER);
            assertWarningsSanitized(appender, 2);
            assertThat(REQUESTS).extracting(RecordedRequest::path)
                    .containsExactly("/cloud/v1/embeddings", "/local/v1/embeddings");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void assertClientFailureSanitized(RuntimeException source, String expectedMessage) {
        HttpEmbeddingProvider provider = provider("/v1", KEY);
        RestClient client = mock(RestClient.class);
        when(client.post()).thenThrow(source);
        ReflectionTestUtils.setField(provider, "restClient", client);

        RetrievalException failure = assertThrows(RetrievalException.class, () -> provider.embedAll(TEXTS));

        assertThat(failure).hasMessage(expectedMessage);
        assertSanitized(failure);
        assertThat(REQUESTS).isEmpty();
    }

    private static HttpEmbeddingProvider provider(String prefix, String apiKey) {
        return new HttpEmbeddingProvider(new RetrievalProperties.Embedding("http", -1, 3, MODEL,
                baseUrl + prefix, apiKey, 5_000, List.of()));
    }

    private static EndpointHealth health(String name) {
        return new EndpointHealth("embedding:" + name, true, 3, 1_000);
    }

    private static TieredEmbeddingProvider chain(EndpointHealth cloud, EndpointHealth local) {
        return new TieredEmbeddingProvider("http-embedding:" + MODEL, 3, List.of(
                new TieredEmbeddingProvider.Tier("cloud", "cloud", provider("/cloud/v1", KEY), cloud,
                        baseUrl + "/cloud/v1"),
                new TieredEmbeddingProvider.Tier("local", "local", provider("/local/v1", ""), local,
                        baseUrl + "/local/v1")));
    }

    private static void assertSanitized(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(MARKER);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private static void assertHealthFailure(EndpointHealth health, String expectedMessage) {
        assertThat(health.snapshot().state()).isEqualTo("down");
        assertThat(health.snapshot().consecutiveFailures()).isEqualTo(1);
        assertThat(health.snapshot().lastError()).isEqualTo(expectedMessage).doesNotContain(MARKER);
    }

    private static void assertWarningsSanitized(ListAppender<ILoggingEvent> appender, int expectedCount) {
        assertThat(appender.list).hasSize(expectedCount).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).doesNotContain(MARKER);
            if (event.getThrowableProxy() != null) {
                assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(MARKER);
            }
        });
    }

    private record StubResponse(int status, String body) { }

    private record RecordedRequest(String method, String path, String body, String authorization,
                                   String contentType) { }
}
