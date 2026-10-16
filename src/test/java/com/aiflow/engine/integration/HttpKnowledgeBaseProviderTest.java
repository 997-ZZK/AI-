package com.aiflow.engine.integration;

import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.retrieval.RetrievalQuery;
import com.aiflow.engine.retrieval.RetrievedChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aiflow.engine.integration.CloudKnowledgeBaseException.Reason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 只向 loopback JDK HttpServer 发合成请求，不读取宿主机环境凭证。 */
class HttpKnowledgeBaseProviderTest {
    private static final String SUCCESS = """
            {"chunks":[{"id":"c1","document_id":"d1","content":"synthetic text","score":82.5,
            "chunk_index":4,"title":"Manual","section":"Usage","page_from":2,"page_to":3}]}
            """;
    private static HttpServer server;
    private static ExecutorService executor;
    private static String baseUrl;
    private static final ConcurrentLinkedQueue<Stub> responses = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<String> body = new AtomicReference<>();
    private static final AtomicReference<String> auth = new AtomicReference<>();
    private static final AtomicReference<String> path = new AtomicReference<>();
    private static final AtomicInteger requests = new AtomicInteger();
    private static final AtomicInteger redirected = new AtomicInteger();
    private static volatile CountDownLatch arrived;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            arrived.countDown();
            if (exchange.getRequestURI().getPath().equals("/leaked")) redirected.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            Stub response = responses.poll();
            if (response == null) response = new Stub(200, SUCCESS, 0, null);
            try {
                if (response.delayMs > 0 && !response.delayBody) Thread.sleep(response.delayMs);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (response.location != null) exchange.getResponseHeaders().set("Location", response.location);
                if (response.retryAfter != null) exchange.getResponseHeaders().set("Retry-After", response.retryAfter);
                byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(response.status, bytes.length);
                if (response.delayBody && bytes.length > 0) {
                    exchange.getResponseBody().write(bytes, 0, 1);
                    exchange.getResponseBody().flush();
                    Thread.sleep(response.delayMs);
                    exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                } else {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 超时/限长测试会主动断开连接。
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        executor.close();
    }

    @BeforeEach
    void reset() {
        responses.clear();
        body.set(null);
        auth.set(null);
        path.set(null);
        requests.set(0);
        redirected.set(0);
        arrived = new CountDownLatch(1);
    }

    @Test
    void sendsDefaultGatewayContractAndPreservesRawScoreAndCitation() throws Exception {
        List<RetrievedChunk> result = provider(http(4096, 1), 2000).retrieve(query());
        assertThat(path.get()).isEqualTo("/v1/retrieve");
        assertThat(new ObjectMapper().readTree(body.get())).isEqualTo(new ObjectMapper().readTree("""
                {"dataset_id":"synthetic-dataset","query":"synthetic question","top_k":3}
                """));
        assertThat(auth.get()).isEqualTo("Bearer synthetic-test-token");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().rawScore()).isEqualTo(82.5);
        assertThat(result.getFirst().score()).isEqualTo(82.5);
        assertThat(result.getFirst().chunkIndex()).isEqualTo(4);
        assertThat(result.getFirst().source()).isEqualTo("cloud-test");
        assertThat(result.getFirst().citationLabel()).isEqualTo("Manual > Usage p.2-3");
        assertThat(result.getFirst().contentHash()).hasSize(64);
    }

    @Test
    void keepsMissingIndexUnknown() {
        responses.add(new Stub(200, SUCCESS.replace("\"chunk_index\":4,", ""), 0, null));
        assertThat(provider(http(4096, 1), 2000).retrieve(query()).getFirst().chunkIndex()).isEqualTo(-1);
    }

    @Test
    void doesNotRetryAuthenticationAndDoesNotLeakBodyOrCredential() {
        responses.add(new Stub(401, "vendor-sensitive-body synthetic-test-token", 0, null));
        assertThatThrownBy(() -> provider(http(4096, 3), 2000).retrieve(query()))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class, error -> {
                    assertThat(error.reason()).isEqualTo(AUTHENTICATION);
                    assertThat(error.httpStatus()).isEqualTo(401);
                    assertThat(error.retryable()).isFalse();
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getMessage()).doesNotContain("vendor-sensitive", "synthetic-test-token", baseUrl);
                });
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void retriesOnlyExplicitBoundedTransientFailures() {
        responses.add(new Stub(429, "private rate-limit details", 0, null));
        responses.add(new Stub(503, "private outage details", 0, null));
        assertThat(provider(http(4096, 3), 2000).retrieve(query())).hasSize(1);
        assertThat(requests.get()).isEqualTo(3);
    }

    @Test
    void defaultsToOneAttemptAndReportsRateLimitMetadata() {
        responses.add(new Stub(429, "private rate-limit details", 0, null));
        assertThatThrownBy(() -> provider(http(4096, 1), 2000).retrieve(query()))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class, error -> {
                    assertThat(error.reason()).isEqualTo(RATE_LIMITED);
                    assertThat(error.retryable()).isTrue();
                });
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void rejectsRedirectWithoutForwardingAuthorization() {
        responses.add(new Stub(302, "{}", 0, baseUrl.replace("/v1", "/leaked")));
        assertThatThrownBy(() -> provider(http(4096, 3), 2000).retrieve(query()))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class, error -> {
                    assertThat(error.httpStatus()).isEqualTo(302);
                    assertThat(error.retryable()).isFalse();
                });
        assertThat(requests.get()).isEqualTo(1);
        assertThat(redirected.get()).isZero();
    }

    @Test
    void boundsResponseBeforeJsonParsingAndDoesNotRetry() {
        responses.add(new Stub(200, "sensitive".repeat(1024), 0, null));
        assertThatThrownBy(() -> provider(http(128, 3), 2000).retrieve(query()))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class,
                        error -> assertThat(error.reason()).isEqualTo(RESPONSE_TOO_LARGE));
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void timesOutWholeResponseWithFixedError() {
        responses.add(new Stub(200, SUCCESS, 600, null));
        assertThatThrownBy(() -> provider(http(4096, 1), 150).retrieve(query()))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class, error -> {
                    assertThat(error.reason()).isEqualTo(TIMEOUT);
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void deadlineAlsoCoversBodyAfterHeadersArrive() {
        responses.add(new Stub(200, SUCCESS, 600, null, null, true));
        assertThatThrownBy(() -> provider(http(4096, 1), 150).retrieve(query()))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class,
                        error -> assertThat(error.reason()).isEqualTo(TIMEOUT));
    }

    @Test
    void retriesShareOneTotalDeadline() {
        responses.add(new Stub(503, "{}", 300, null));
        responses.add(new Stub(200, SUCCESS, 300, null));
        assertThatThrownBy(() -> provider(http(4096, 2), 500).retrieve(query()))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class,
                        error -> assertThat(error.reason()).isEqualTo(TIMEOUT));
        assertThat(requests.get()).isBetween(1, 2);
    }

    @Test
    void retryAfterHeaderStopsAutomaticRetryWithoutEarlyCall() {
        responses.add(new Stub(429, "private details", 0, null, "60", false));
        assertThatThrownBy(() -> provider(http(4096, 3), 2000).retrieve(query()))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class, error -> {
                    assertThat(error.reason()).isEqualTo(RATE_LIMITED);
                    assertThat(error.retryable()).isFalse();
                });
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void checksCredentialLazilyAndNeverCallsNetworkWhenMissing() {
        RetrievalProperties.Cloud cloud = cloud(http(4096, 1), 2000);
        AtomicInteger lookups = new AtomicInteger();
        HttpKnowledgeBaseProvider provider = new HttpKnowledgeBaseProvider(cloud,
                new JsonKnowledgeBaseProtocol(cloud.http().mapping()), variable -> {
                    lookups.incrementAndGet();
                    return null;
                });
        assertThat(lookups.get()).isZero();
        assertThatThrownBy(() -> provider.retrieve(query())).isInstanceOf(CloudKnowledgeBaseException.class);
        assertThat(lookups.get()).isEqualTo(1);
        assertThat(requests.get()).isZero();
    }

    @Test
    void rejectsLegacyInlineCredentialWithExplicitFixedMigrationFailure() {
        RetrievalProperties.Cloud config = new RetrievalProperties.Cloud(true, "http", "cloud", 0, 100,
                baseUrl, "synthetic-old-secret", "dataset", 1000);
        assertThat(config.toString()).doesNotContain("synthetic-old-secret", baseUrl, "dataset");
        assertThatThrownBy(() -> new HttpKnowledgeBaseProvider(config,
                new JsonKnowledgeBaseProtocol(config.http().mapping()), ignored -> null))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class,
                        error -> assertThat(error.reason()).isEqualTo(CONFIGURATION));
    }

    @Test
    void cancellationDoesNotStartNetwork() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> provider(http(4096, 1), 2000).retrieve(query()))
                    .isInstanceOfSatisfying(CloudKnowledgeBaseException.class,
                            error -> assertThat(error.reason()).isEqualTo(CANCELED));
            assertThat(requests.get()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsOversizedRequestBeforeNetwork() {
        var options = new RetrievalProperties.CloudHttp("/retrieve", "SYNTHETIC_KB_TOKEN", "Authorization", "Bearer",
                4096, 1, RetrievalProperties.CloudMapping.defaults(), 8, 1, false);
        assertThatThrownBy(() -> provider(options, 2000).retrieve(query()))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class,
                        error -> assertThat(error.reason()).isEqualTo(REQUEST_TOO_LARGE));
        assertThat(requests.get()).isZero();
    }

    @Test
    void boundsConcurrencyAndReleasesPermitAfterCompletion() throws Exception {
        responses.add(new Stub(200, SUCCESS, 300, null));
        var options = new RetrievalProperties.CloudHttp("/retrieve", "SYNTHETIC_KB_TOKEN", "Authorization", "Bearer",
                4096, 1, RetrievalProperties.CloudMapping.defaults(), 4096, 1, false);
        var provider = provider(options, 2000);
        CompletableFuture<List<RetrievedChunk>> first = CompletableFuture.supplyAsync(() -> provider.retrieve(query()));
        try {
            assertThat(arrived.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> provider.retrieve(query())).isInstanceOfSatisfying(CloudKnowledgeBaseException.class,
                    error -> assertThat(error.reason()).isEqualTo(OVERLOADED));
        } finally {
            assertThat(first.get(3, TimeUnit.SECONDS)).hasSize(1);
        }
        assertThat(provider.retrieve(query())).hasSize(1);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void rejectsAutomaticPostRetriesWithoutExplicitSafetyDeclaration() {
        var options = new RetrievalProperties.CloudHttp("/retrieve", "", "Authorization", "Bearer",
                4096, 2, RetrievalProperties.CloudMapping.defaults());
        assertThatThrownBy(() -> provider(options, 2000)).isInstanceOfSatisfying(CloudKnowledgeBaseException.class,
                error -> assertThat(error.reason()).isEqualTo(CONFIGURATION));
    }

    @Test
    void rejectsNonAsciiAndControlCharactersInSyntheticCredentialWithoutLeak() {
        var config = cloud(http(4096, 1), 2000);
        for (String synthetic : List.of("synthetic-secret\u0001", "synthetic-secret\u0100")) {
            var provider = new HttpKnowledgeBaseProvider(config, new JsonKnowledgeBaseProtocol(config.http().mapping()),
                    ignored -> synthetic);
            assertThatThrownBy(() -> provider.retrieve(query())).isInstanceOfSatisfying(CloudKnowledgeBaseException.class, error -> {
                assertThat(error.reason()).isEqualTo(CONFIGURATION);
                assertThat(error.getMessage()).doesNotContain("synthetic-secret");
                assertThat(error.getCause()).isNull();
            });
        }
        assertThat(requests.get()).isZero();
    }

    @Test
    void rejectsRestrictedHeaderNames() {
        var options = new RetrievalProperties.CloudHttp("/retrieve", "SYNTHETIC_KB_TOKEN", "Host", "Bearer",
                4096, 1, RetrievalProperties.CloudMapping.defaults());
        assertThatThrownBy(() -> provider(options, 2000)).isInstanceOfSatisfying(CloudKnowledgeBaseException.class,
                error -> assertThat(error.reason()).isEqualTo(CONFIGURATION));
    }

    @Test
    void wrapsUntrustedCustomProtocolErrorsWithoutOriginalCause() {
        KnowledgeBaseProtocol broken = new KnowledgeBaseProtocol() {
            public byte[] encode(RetrievalQuery query, String datasetId) { throw new IllegalArgumentException("private query"); }
            public List<RetrievedChunk> decode(String source, byte[] response) { return List.of(); }
        };
        var provider = new HttpKnowledgeBaseProvider(cloud(http(4096, 1), 2000), broken, ignored -> "synthetic");
        assertThatThrownBy(() -> provider.retrieve(query())).isInstanceOfSatisfying(CloudKnowledgeBaseException.class, error -> {
            assertThat(error.reason()).isEqualTo(CONFIGURATION);
            assertThat(error.getMessage()).doesNotContain("private query");
            assertThat(error.getCause()).isNull();
        });
    }

    private HttpKnowledgeBaseProvider provider(RetrievalProperties.CloudHttp http, long timeout) {
        RetrievalProperties.Cloud cloud = cloud(http, timeout);
        return new HttpKnowledgeBaseProvider(cloud, new JsonKnowledgeBaseProtocol(http.mapping()),
                variable -> "SYNTHETIC_KB_TOKEN".equals(variable) ? "synthetic-test-token" : null);
    }

    private RetrievalProperties.Cloud cloud(RetrievalProperties.CloudHttp http, long timeout) {
        return new RetrievalProperties.Cloud(true, "http", "cloud-test", 0, 100, baseUrl,
                "", "synthetic-dataset", timeout, http);
    }

    private RetrievalProperties.CloudHttp http(int limit, int attempts) {
        return new RetrievalProperties.CloudHttp("/retrieve", "SYNTHETIC_KB_TOKEN", "Authorization", "Bearer",
                limit, attempts, RetrievalProperties.CloudMapping.defaults(), 1024 * 1024, 4, attempts > 1);
    }

    private RetrievalQuery query() {
        return new RetrievalQuery("synthetic question", null, "kb", 3, 0, Map.of());
    }

    private record Stub(int status, String body, long delayMs, String location, String retryAfter, boolean delayBody) {
        private Stub(int status, String body, long delayMs, String location) {
            this(status, body, delayMs, location, null, false);
        }
    }
}
