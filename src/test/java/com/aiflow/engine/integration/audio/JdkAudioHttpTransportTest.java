package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AudioProviderException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class JdkAudioHttpTransportTest {

    private HttpServer server;
    private ExecutorService executor;
    private URI endpoint;

    @BeforeEach
    void startLocalStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stub");
    }

    @AfterEach
    void stopLocalStub() throws InterruptedException {
        server.stop(0);
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void postsOnlyExplicitHeadersAndReturnsControlledResponse() {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();
        server.createContext("/stub", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(exchange.getRequestBody().readAllBytes());
            reply(exchange, 200, "application/json", "{\"text\":\"fixture\"}".getBytes(StandardCharsets.UTF_8));
        });
        byte[] payload = "offline-fixture".getBytes(StandardCharsets.UTF_8);
        try (var transport = transport(1024, 1024, 1, Duration.ofSeconds(2))) {
            AudioHttpResponse response = transport.exchange(request(payload, false));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.contentType()).isEqualTo("application/json");
            assertThat(response.body()).isEqualTo("{\"text\":\"fixture\"}".getBytes(StandardCharsets.UTF_8));
            assertThat(received.get()).isEqualTo(payload);
            assertThat(authorization.get()).isEqualTo("Bearer synthetic-only");
        }
    }

    @ParameterizedTest
    @CsvSource({"400,REJECTED", "401,REJECTED", "403,REJECTED", "429,REJECTED", "500,CALL_FAILED",
            "502,CALL_FAILED", "503,CALL_FAILED", "504,CALL_FAILED", "202,INVALID_RESPONSE", "302,INVALID_RESPONSE"})
    void mapsStatusesWithoutRetainingVendorBody(int status, AudioProviderException.Reason reason) {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/stub", exchange -> {
            calls.incrementAndGet();
            reply(exchange, status, "text/plain", "vendor-private-body".getBytes(StandardCharsets.UTF_8));
        });
        try (var transport = transport(1024, 1024, 1, Duration.ofSeconds(2))) {
            AudioProviderException failure = catchThrowableOfType(() -> transport.exchange(request(new byte[1], true)),
                    AudioProviderException.class);
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.toString()).doesNotContain("vendor-private-body", "synthetic-only", "/stub");
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void refusesRedirectWithoutForwardingAuthorization() {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/stub", exchange -> {
            exchange.getResponseHeaders().set("Location", endpoint.resolve("/redirected").toString());
            reply(exchange, 307, "text/plain", new byte[0]);
        });
        server.createContext("/redirected", exchange -> {
            redirected.incrementAndGet();
            reply(exchange, 200, "text/plain", new byte[1]);
        });
        try (var transport = transport(1024, 1024, 3, Duration.ofSeconds(2))) {
            assertReason(() -> transport.exchange(request(new byte[1], true)), AudioProviderException.Reason.INVALID_RESPONSE);
            assertThat(redirected).hasValue(0);
        }
    }

    @Test
    void rejectsOversizedRequestBeforeSending() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/stub", exchange -> {
            calls.incrementAndGet();
            reply(exchange, 200, "application/octet-stream", new byte[1]);
        });
        try (var transport = transport(4, 1024, 1, Duration.ofSeconds(2))) {
            assertReason(() -> transport.exchange(request(new byte[5], false)), AudioProviderException.Reason.CALL_FAILED);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void limitsCumulativeChunkedResponseBeforeCollectingWholeBody() {
        server.createContext("/stub", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(new byte[64]);
                output.flush();
                output.write(new byte[64]);
            }
        });
        try (var transport = transport(1024, 100, 1, Duration.ofSeconds(2))) {
            assertReason(() -> transport.exchange(request(new byte[1], false)), AudioProviderException.Reason.INVALID_RESPONSE);
        }
    }

    @Test
    void totalDeadlineIncludesBodyAfterHeaders() {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/stub", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, 128);
            try (var output = exchange.getResponseBody()) {
                output.write(0);
                output.flush();
                bodyStarted.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try (var transport = transport(1024, 1024, 3, Duration.ofMillis(700))) {
            long started = System.nanoTime();
            assertReason(() -> transport.exchange(request(new byte[1], true)), AudioProviderException.Reason.TIMEOUT);
            assertThat(bodyStarted.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
        }
    }

    @Test
    void retriesOnlyExplicitlySafeTransientStatus() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/stub", exchange -> {
            int status = calls.incrementAndGet() == 1 ? 429 : 200;
            reply(exchange, status, "application/octet-stream", new byte[]{1});
        });
        try (var transport = transport(1024, 1024, 3, Duration.ofSeconds(2))) {
            assertThat(transport.exchange(request(new byte[1], true)).status()).isEqualTo(200);
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void retryAfterHeaderPreventsPrematureAutomaticRetry() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/stub", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Retry-After", "999999999");
            reply(exchange, 429, "application/octet-stream", new byte[1]);
        });
        try (var transport = transport(1024, 1024, 3, Duration.ofSeconds(2))) {
            assertReason(() -> transport.exchange(request(new byte[1], true)), AudioProviderException.Reason.REJECTED);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void limitsConcurrencyAndCloseDoesNotWaitForStalledBody() throws InterruptedException {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/stub", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 128);
            try (var output = exchange.getResponseBody()) {
                output.write(0);
                output.flush();
                arrived.countDown();
                try {
                    release.await(4, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        var options = new JdkAudioHttpTransport.Options(Duration.ofSeconds(1), Duration.ofSeconds(5),
                1024, 1024, 3, Duration.ZERO, 1);
        try (var transport = new JdkAudioHttpTransport(options)) {
            AtomicReference<AudioProviderException> stopped = new AtomicReference<>();
            Thread first = Thread.ofVirtual().start(() -> stopped.set(catchThrowableOfType(
                    () -> transport.exchange(request(new byte[1], true)), AudioProviderException.class)));
            assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue();
            assertReason(() -> transport.exchange(request(new byte[1], false)), AudioProviderException.Reason.UNAVAILABLE);
            assertThat(calls).hasValue(1);
            long beforeClose = System.nanoTime();
            transport.close();
            assertThat(Duration.ofNanos(System.nanoTime() - beforeClose)).isLessThan(Duration.ofSeconds(1));
            first.join(2000);
            assertThat(first.isAlive()).isFalse();
            assertThat(stopped.get()).isNotNull();
            assertThat(stopped.get().reason()).isEqualTo(AudioProviderException.Reason.CALL_FAILED);
        } finally {
            release.countDown();
        }
    }

    @Test
    void inFlightInterruptStopsWithoutRetryAndPreservesFlag() throws InterruptedException {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/stub", exchange -> {
            calls.incrementAndGet();
            arrived.countDown();
            try {
                release.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            reply(exchange, 503, "text/plain", new byte[1]);
        });
        try (var transport = transport(1024, 1024, 3, Duration.ofSeconds(5))) {
            AtomicReference<AudioProviderException> stopped = new AtomicReference<>();
            AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
            Thread first = Thread.ofVirtual().start(() -> {
                stopped.set(catchThrowableOfType(() -> transport.exchange(request(new byte[1], true)),
                        AudioProviderException.class));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue();
            first.interrupt();
            first.join(2000);
            assertThat(first.isAlive()).isFalse();
            assertThat(stopped.get()).isNotNull();
            assertThat(stopped.get().reason()).isEqualTo(AudioProviderException.Reason.CALL_FAILED);
            assertThat(interrupted).hasValue(true);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void neverRetriesUnsafeRequestEvenWhenMultipleAttemptsConfigured() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/stub", exchange -> {
            calls.incrementAndGet();
            reply(exchange, 503, "application/octet-stream", new byte[1]);
        });
        try (var transport = transport(1024, 1024, 3, Duration.ofSeconds(2))) {
            assertReason(() -> transport.exchange(request(new byte[1], false)), AudioProviderException.Reason.CALL_FAILED);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void stopsSafeRetryAtAttemptLimit() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/stub", exchange -> {
            calls.incrementAndGet();
            reply(exchange, 503, "application/octet-stream", new byte[1]);
        });
        try (var transport = transport(1024, 1024, 3, Duration.ofSeconds(2))) {
            assertReason(() -> transport.exchange(request(new byte[1], true)), AudioProviderException.Reason.CALL_FAILED);
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void preservesPreexistingInterruptAndDoesNotSend() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/stub", exchange -> {
            calls.incrementAndGet();
            reply(exchange, 200, "application/octet-stream", new byte[1]);
        });
        try (var transport = transport(1024, 1024, 3, Duration.ofSeconds(2))) {
            Thread.currentThread().interrupt();
            try {
                assertReason(() -> transport.exchange(request(new byte[1], true)), AudioProviderException.Reason.CALL_FAILED);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(calls).hasValue(0);
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void requestAndResponseDefendBytesAndHideSensitiveFields() {
        byte[] original = new byte[]{1, 2};
        AudioHttpRequest request = request(original, false);
        AudioHttpResponse response = new AudioHttpResponse(200, "audio/wav", original);
        original[0] = 9;
        request.body()[0] = 8;
        response.body()[0] = 7;
        assertThat(request.body()).containsExactly(1, 2);
        assertThat(response.body()).containsExactly(1, 2);
        assertThat(request.toString()).doesNotContain("synthetic-only", "/stub", "Authorization");
        assertThat(response.toString()).isEqualTo("AudioHttpResponse[status=200, bodyBytes=2]");
    }

    @ParameterizedTest
    @CsvSource({"http://example.invalid/stub", "https://user:private@example.invalid/stub",
            "https://example.invalid/stub?token=private", "https://example.invalid/stub#private", "file:///tmp/audio"})
    void rejectsUnsafeEndpointConfigurationsBeforeAnyNetwork(String value) {
        assertThatThrownBy(() -> new AudioHttpRequest(URI.create(value), "application/json", "audio/wav",
                Map.of(), new byte[1], false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("无效音频 HTTP 请求配置");
    }

    @Test
    void rejectsHeaderInjectionAndConflictingCaseInsensitiveNames() {
        assertThatThrownBy(() -> new AudioHttpRequest(endpoint, "application/json", "audio/wav",
                Map.of("Authorization", "Bearer fixture\r\nX-Injected: private"), new byte[1], false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("无效音频 HTTP 请求配置");
        assertThatThrownBy(() -> new AudioHttpRequest(endpoint, "application/json", "audio/wav",
                Map.of("Authorization", "Bearer fixture", "authorization", "Bearer conflicting"), new byte[1], false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("无效音频 HTTP 请求配置");
    }

    private JdkAudioHttpTransport transport(int maxRequest, int maxResponse, int attempts, Duration timeout) {
        return new JdkAudioHttpTransport(new JdkAudioHttpTransport.Options(Duration.ofSeconds(1), timeout,
                maxRequest, maxResponse, attempts, Duration.ZERO));
    }

    private AudioHttpRequest request(byte[] body, boolean safe) {
        return new AudioHttpRequest(endpoint, "application/octet-stream", "application/json",
                Map.of("Authorization", "Bearer synthetic-only"), body, safe);
    }

    private static void reply(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void assertReason(Runnable action, AudioProviderException.Reason reason) {
        AudioProviderException failure = catchThrowableOfType(action::run, AudioProviderException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.getCause()).isNull();
    }
}
