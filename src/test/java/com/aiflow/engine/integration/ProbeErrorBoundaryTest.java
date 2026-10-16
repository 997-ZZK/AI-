package com.aiflow.engine.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.config.AudioProperties;
import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.provider.CapabilityRegistry;
import com.aiflow.engine.provider.EndpointHealth;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.provider.mock.MockChatProvider;
import com.aiflow.engine.retrieval.mock.MockEmbeddingProvider;
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
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Probe diagnostics are public; all private upstream values used here are synthetic. */
@Timeout(10)
class ProbeErrorBoundaryTest {
    private static final String PROVIDER_ID = "probe-boundary";
    private static final String MODEL = "gpt-4o-mini";
    private static final String KEY_MARKER = "synthetic-probe-key-private";
    private static final String PROMPT_MARKER = "synthetic-probe-prompt-private";
    private static final String NETWORK_MARKER = "synthetic-probe-network-private";
    private static final String JSON_MARKER = "synthetic-probe-json-private";
    private static final String[] PRIVATE_MARKERS = {KEY_MARKER, PROMPT_MARKER, NETWORK_MARKER, JSON_MARKER};
    private static final String LISTED = "{\"data\":[{\"id\":\"" + MODEL + "\"}]}";
    private static final String PRIVATE_BODY = "{\"error\":{\"key\":\"" + KEY_MARKER
            + "\",\"prompt\":\"" + PROMPT_MARKER + "\"}}";

    private final Logger logger = (Logger) LoggerFactory.getLogger("com.aiflow.engine.integration");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final AtomicReference<StubResponse> response = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final ObjectMapper mapper = new ObjectMapper();
    private Level previousLevel;
    private HttpServer server;
    private String baseUrl;
    private HttpEndpointFactory endpoints;
    private EndpointHealth health;
    private EndpointProbe probe;
    private EndpointProbeScheduler scheduler;
    private CapabilityRegistry capabilities;

    @BeforeEach
    void start() throws IOException {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        response.set(new StubResponse(200, LISTED));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            requests.incrementAndGet();
            StubResponse selected = response.get();
            try {
                reply(exchange, selected.status(), selected.body());
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        ProviderProperties defaults = ProviderProperties.defaults();
        ProviderProperties.Endpoint endpoint = new ProviderProperties.Endpoint(baseUrl, MODEL, KEY_MARKER,
                Set.of("chat"), true, 1_000, "local", "", 0);
        ProviderProperties properties = new ProviderProperties("auto", new String[]{PROVIDER_ID, "mock"},
                List.of("mock"), defaults.mock(), Map.of(PROVIDER_ID, endpoint),
                new ProviderProperties.Probe(true, 60_000, 1_000), defaults.breaker());
        endpoints = new HttpEndpointFactory(properties, mapper);
        health = endpoints.health(PROVIDER_ID, true);
        probe = (EndpointProbe) endpoints.probes().getFirst();
        scheduler = new EndpointProbeScheduler(endpoints, properties);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        ProviderRouter router = new ProviderRouter(List.of(new MockChatProvider(properties)), properties, endpoints);
        capabilities = new CapabilityRegistry(router, new MockEmbeddingProvider("mock-embedding", 1024), endpoints,
                RetrievalProperties.defaults(), AudioProperties.defaults(), beans.getBeanProvider(AsrProvider.class),
                beans.getBeanProvider(TtsProvider.class));
        logs.list.clear();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
    }

    @Test
    void missingModelDoesNotCopyUpstreamIdsIntoHealthLogsOrPublicCapabilities() {
        response.set(new StubResponse(200, "{\"data\":[{\"id\":\"" + KEY_MARKER
                + "\"},{\"id\":\"" + PROMPT_MARKER + "\"}]}"));

        scheduler.probeAll();

        String expected = "端点在线但模型列表中不含 " + MODEL;
        assertFailed(expected);
        CapabilityRegistry.Candidate candidate = chatCandidate();
        assertThat(candidate.model()).isEqualTo(MODEL);
        assertThat(candidate.detail()).isEqualTo("down：" + expected);
        assertThat(candidate.available()).isFalse();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).isEqualTo("probe " + PROVIDER_ID + " -> down");
        });
        assertPublicAndLogsSafe();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 503})
    void httpErrorBodyNeverBecomesProbeDiagnostic(int status) {
        response.set(new StubResponse(status, PRIVATE_BODY));

        assertThat(probe.probe()).isFalse();

        assertFailed(status == 503 ? "探活返回 HTTP 503" : "探活鉴权失败 HTTP " + status);
        assertThat(requests.get()).isEqualTo(1);
        assertPublicAndLogsSafe();
    }

    @Test
    void invalidAuthorizationHeaderReturnsFalseAndOnlyRecordsOneFailure() {
        EndpointProbe invalid = new EndpointProbe(PROVIDER_ID, health, baseUrl,
                KEY_MARKER + "\r\nX-Synthetic: " + PROMPT_MARKER, 1_000, MODEL);

        assertThat(invalid.probe()).isFalse();

        assertFailed("探活请求构造失败");
        assertThat(requests.get()).isZero();
        assertPublicAndLogsSafe();
    }

    @Test
    void loopbackConnectionFailureUsesFixedDiagnostic() throws IOException {
        int unusedPort;
        try (ServerSocket reservation = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = reservation.getLocalPort();
        }
        EndpointProbe unavailable = new EndpointProbe(PROVIDER_ID, health,
                "http://127.0.0.1:" + unusedPort + "/" + NETWORK_MARKER, KEY_MARKER, 1_000, MODEL);

        assertThat(unavailable.probe()).isFalse();

        assertFailed("探活失败: 网络/协议错误");
        assertThat(requests.get()).isZero();
        assertPublicAndLogsSafe();
    }

    @Test
    void transportIOExceptionDoesNotPublishItsPrivateMessage() throws Exception {
        useFailure(new IOException(NETWORK_MARKER + " " + KEY_MARKER + " " + PROMPT_MARKER));

        scheduler.probeAll();

        assertFailed("探活失败: 网络/协议错误");
        assertThat(requests.get()).isZero();
        assertThat(logs.list).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).isEqualTo("probe " + PROVIDER_ID + " -> down"));
        assertPublicAndLogsSafe();
    }

    @Test
    void interruptionRestoresFlagAndRetainsExistingOneFailureSemantics() throws Exception {
        useFailure(new InterruptedException(NETWORK_MARKER));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();

        try {
            assertThat(probe.probe()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertFailed("探活被中断");
        } finally {
            Thread.interrupted();
        }

        assertThat(requests.get()).isZero();
        assertPublicAndLogsSafe();
    }

    @Test
    void successfulProbeClearsEarlierFailureAndRestoresPublicAvailability() {
        response.set(new StubResponse(401, PRIVATE_BODY));
        assertThat(probe.probe()).isFalse();
        assertFailed("探活鉴权失败 HTTP 401");
        response.set(new StubResponse(200, LISTED));

        assertThat(probe.probe()).isTrue();

        assertHealthy();
        assertThat(health.snapshot().lastSuccessAt()).isNotNull();
        assertThat(health.snapshot().lastFailureAt()).isNotNull();
        assertThat(chatCandidate().available()).isTrue();
        assertThat(chatCandidate().detail()).isEqualTo("up");
        assertThat(requests.get()).isEqualTo(2);
        assertPublicAndLogsSafe();
    }

    @ParameterizedTest
    @ValueSource(strings = {"synthetic-probe-json-private {", "{\"data\":[{\"object\":\"synthetic-probe-prompt-private\"}]}"})
    void unknownModelListKeepsEndpointReachableWithoutPublishingBody(String body) {
        response.set(new StubResponse(200, body));

        assertThat(probe.probe()).isTrue();

        assertHealthy();
        assertPublicAndLogsSafe();
    }

    @Test
    void explicitEmptyAndNullListsRemainUnavailableWithoutPrivateMetadata() {
        String[] emptyLists = {"[]", "null"};
        for (int index = 0; index < emptyLists.length; index++) {
            response.set(new StubResponse(200, "{\"data\":" + emptyLists[index]
                    + ",\"private\":\"" + PROMPT_MARKER + "\"}"));

            assertThat(probe.probe()).isFalse();

            assertThat(health.snapshot().state()).isEqualTo("down");
            assertThat(health.snapshot().consecutiveFailures()).isEqualTo(index + 1);
            assertThat(health.snapshot().lastError()).isEqualTo("端点在线但模型清单为空：配置的 " + MODEL
                    + " 尚未部署（拉取后下次探活自动恢复）");
            assertPublicAndLogsSafe();
        }
    }

    @Test
    void prefixedVersionedModelStillMatchesWithoutPublishingOtherModelIds() {
        response.set(new StubResponse(200, "{\"data\":[{\"id\":\"vendor/" + MODEL
                + "-2026-10-14\"},{\"id\":\"" + KEY_MARKER + "\"}]}"));

        assertThat(probe.probe()).isTrue();

        assertHealthy();
        assertThat(chatCandidate().available()).isTrue();
        assertPublicAndLogsSafe();
    }

    @Test
    void otherClientStatusRemainsReachableWithoutPublishingErrorBody() {
        response.set(new StubResponse(404, PRIVATE_BODY));

        assertThat(probe.probe()).isTrue();

        assertHealthy();
        assertPublicAndLogsSafe();
    }

    private void useFailure(Exception failure) throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(failure);
        ReflectionTestUtils.setField(probe, "httpClient", client);
    }

    private void assertFailed(String message) {
        assertThat(health.snapshot().state()).isEqualTo("down");
        assertThat(health.snapshot().lastError()).isEqualTo(message).doesNotContain(PRIVATE_MARKERS);
        assertThat(health.snapshot().consecutiveFailures()).isEqualTo(1);
        assertThat(health.snapshot().lastProbeAt()).isNotNull();
        assertThat(health.snapshot().lastFailureAt()).isNotNull();
    }

    private void assertHealthy() {
        assertThat(health.snapshot().state()).isEqualTo("up");
        assertThat(health.snapshot().consecutiveFailures()).isZero();
        assertThat(health.snapshot().lastError()).isNull();
        assertThat(health.snapshot().lastProbeAt()).isNotNull();
    }

    private void assertPublicAndLogsSafe() {
        assertThat(health.snapshot().toString()).doesNotContain(PRIVATE_MARKERS);
        assertThat(mapper.valueToTree(capabilities.report()).toString()).doesNotContain(PRIVATE_MARKERS);
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_MARKERS);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private CapabilityRegistry.Candidate chatCandidate() {
        return capabilities.report().channels().stream().filter(channel -> "chat".equals(channel.channel()))
                .flatMap(channel -> channel.candidates().stream()).filter(candidate -> PROVIDER_ID.equals(candidate.id()))
                .findFirst().orElseThrow();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private record StubResponse(int status, String body) { }
}
