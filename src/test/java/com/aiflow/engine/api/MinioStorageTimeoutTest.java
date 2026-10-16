package com.aiflow.engine.api;

import com.aiflow.engine.storage.MinioObjectStorage;
import com.aiflow.engine.storage.StorageException;
import com.aiflow.engine.storage.StorageProperties;
import com.aiflow.engine.storage.StoredObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MinIO HTTP timeout checks use only a loopback JDK server and synthetic credentials.
 *
 * <p>The server handles the SDK's region lookup as well as the S3-shaped requests. Slow
 * handlers are released in {@link #stopStub()} so a failed assertion cannot leave a
 * request thread holding the test JVM open.</p>
 */
@Timeout(15)
class MinioStorageTimeoutTest {

    private static final String BUCKET = "synthetic-bucket";
    private static final String OBJECT = "synthetic/object.txt";
    private static final String BODY = "offline object body";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(1200);
    private static final String TIMEOUT_MESSAGE = "对象存储 timeout 必须介于 1ms 和 2147483647ms 之间";

    private HttpServer server;
    private ExecutorService executor;
    private String endpoint;
    private volatile CountDownLatch blockedHeaders;
    private volatile CountDownLatch blockedBody;
    private volatile SlowTarget slowTarget = SlowTarget.NONE;
    private final ConcurrentHashMap<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private final AtomicInteger dripChunks = new AtomicInteger();
    private final List<OkHttpClient> clients = new ArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() throws InterruptedException {
        CountDownLatch headers = blockedHeaders;
        if (headers != null) {
            headers.countDown();
        }
        CountDownLatch body = blockedBody;
        if (body != null) {
            body.countDown();
        }
        for (OkHttpClient client : clients) {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void normalPutStatAndOpenUseLoopbackStub() throws Exception {
        MinioObjectStorage storage = storage(Duration.ofSeconds(2));

        StoredObject written = storage.put(OBJECT, BODY.getBytes(StandardCharsets.UTF_8), "text/plain");
        Optional<StoredObject> metadata = storage.stat(OBJECT);
        byte[] downloaded;
        try (InputStream stream = storage.open(OBJECT)) {
            downloaded = stream.readAllBytes();
        }

        assertThat(written.key()).isEqualTo(OBJECT);
        assertThat(written.size()).isEqualTo(BODY.length());
        assertThat(written.contentType()).isEqualTo("text/plain");
        assertThat(metadata).isPresent().get().satisfies(value -> {
            assertThat(value.key()).isEqualTo(OBJECT);
            assertThat(value.size()).isEqualTo(BODY.length());
            assertThat(value.contentType()).isEqualTo("text/plain");
        });
        assertThat(downloaded).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(requestCount("HEAD", "/" + BUCKET)).isEqualTo(1);
        assertThat(requestCount("PUT", objectPath())).isEqualTo(1);
        assertThat(requestCount("HEAD", objectPath())).isEqualTo(1);
        assertThat(requestCount("GET", objectPath())).isEqualTo(1);
    }

    @Test
    void configuresConnectWriteReadAndCallTimeoutsTogether() {
        MinioObjectStorage storage = storage(Duration.ofMillis(1234));

        OkHttpClient httpClient = httpClient(storage);

        assertThat(httpClient.connectTimeoutMillis()).isEqualTo(1234);
        assertThat(httpClient.writeTimeoutMillis()).isEqualTo(1234);
        assertThat(httpClient.readTimeoutMillis()).isEqualTo(1234);
        assertThat(httpClient.callTimeoutMillis()).isEqualTo(1234);
    }

    @ParameterizedTest(name = "timeout={0}")
    @MethodSource("validTimeouts")
    void acceptsValidTimeoutsAndTruncatesSubMillisecondPrecision(Duration timeout, int expectedMillis) {
        MinioObjectStorage storage = storage(timeout);

        assertThat(httpClient(storage).callTimeoutMillis()).isEqualTo(expectedMillis);
    }

    static Stream<Arguments> validTimeouts() {
        return Stream.of(
                Arguments.of(Duration.ofNanos(1_999_999), 1),
                Arguments.of(Duration.ofMillis(1), 1),
                Arguments.of(Duration.ofMillis(Integer.MAX_VALUE), Integer.MAX_VALUE));
    }

    @ParameterizedTest(name = "timeout={0}")
    @MethodSource("invalidTimeouts")
    void rejectsInvalidTimeoutsWithoutRetainingConfiguration(Duration timeout) {
        StorageProperties properties = properties(timeout);

        assertThatThrownBy(() -> new MinioObjectStorage(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(TIMEOUT_MESSAGE)
                .hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
    }

    static Stream<Arguments> invalidTimeouts() {
        return Stream.of(
                Arguments.of((Duration) null),
                Arguments.of(Duration.ZERO),
                Arguments.of(Duration.ofNanos(999_999)),
                Arguments.of(Duration.ofMillis(-1)),
                Arguments.of(Duration.ofMillis(Integer.MAX_VALUE).plusNanos(1)),
                Arguments.of(Duration.ofMillis(Integer.MAX_VALUE).plusMillis(1)),
                Arguments.of(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void slowStatResponseHeadersBecomeFixedStatFailure() {
        blockedHeaders = new CountDownLatch(1);
        slowTarget = SlowTarget.STAT;
        MinioObjectStorage storage = storage(REQUEST_TIMEOUT);

        long started = System.nanoTime();
        assertThatThrownBy(() -> storage.stat(OBJECT))
                .isInstanceOf(StorageException.class)
                .hasMessage("对象存储元信息查询失败").hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty())
                .satisfies(error -> assertThat(((StorageException) error).kind())
                        .isEqualTo(StorageException.Kind.STAT));
        assertCompletesWithin(started, 7);
        assertThat(requestCount("HEAD", objectPath())).isEqualTo(1);
    }

    @Test
    void slowOpenResponseHeadersBecomeFixedReadFailure() {
        blockedHeaders = new CountDownLatch(1);
        slowTarget = SlowTarget.OPEN;
        MinioObjectStorage storage = storage(REQUEST_TIMEOUT);

        long started = System.nanoTime();
        assertThatThrownBy(() -> storage.open(OBJECT))
                .isInstanceOf(StorageException.class)
                .hasMessage("对象存储读取失败").hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty())
                .satisfies(error -> assertThat(((StorageException) error).kind())
                        .isEqualTo(StorageException.Kind.READ));
        assertCompletesWithin(started, 7);
        assertThat(requestCount("GET", objectPath())).isEqualTo(1);
    }

    @Test
    void slowBucketCheckBecomesFixedBucketFailure() {
        blockedHeaders = new CountDownLatch(1);
        slowTarget = SlowTarget.BUCKET;
        MinioObjectStorage storage = storage(REQUEST_TIMEOUT);

        long started = System.nanoTime();
        assertThatThrownBy(() -> storage.put(OBJECT, new byte[]{1}, "text/plain"))
                .isInstanceOf(StorageException.class)
                .hasMessage("对象存储不可用").hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty())
                .satisfies(error -> assertThat(((StorageException) error).kind())
                        .isEqualTo(StorageException.Kind.BUCKET));
        assertCompletesWithin(started, 7);
        assertThat(requestCount("HEAD", "/" + BUCKET)).isEqualTo(1);
        assertThat(requestCount("PUT", objectPath())).isZero();
    }

    @Test
    void slowWriteResponseBecomesFixedWriteFailure() {
        blockedHeaders = new CountDownLatch(1);
        slowTarget = SlowTarget.WRITE;
        MinioObjectStorage storage = storage(REQUEST_TIMEOUT);

        // The bucket HEAD is answered immediately; the PUT response headers are held.
        long started = System.nanoTime();
        assertThatThrownBy(() -> storage.put(OBJECT, new byte[]{1}, "text/plain"))
                .isInstanceOf(StorageException.class)
                .hasMessage("对象存储写入失败").hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty())
                .satisfies(error -> assertThat(((StorageException) error).kind())
                        .isEqualTo(StorageException.Kind.WRITE));
        assertCompletesWithin(started, 7);
        assertThat(requestCount("HEAD", "/" + BUCKET)).isEqualTo(1);
        assertThat(requestCount("PUT", objectPath())).isEqualTo(1);
    }

    @Test
    void slowObjectBodyIsInterruptedAfterOpen() throws Exception {
        blockedBody = new CountDownLatch(1);
        MinioObjectStorage storage = storage(REQUEST_TIMEOUT);

        long started = System.nanoTime();
        try (InputStream stream = storage.open(OBJECT)) {
            assertThatThrownBy(stream::readAllBytes).isInstanceOf(IOException.class);
        }
        assertCompletesWithin(started, 7);
        assertThat(requestCount("GET", objectPath())).isEqualTo(1);
    }

    @Test
    void slowMakeBucketResponseBecomesFixedBucketFailure() {
        blockedHeaders = new CountDownLatch(1);
        slowTarget = SlowTarget.MAKE_BUCKET;

        MinioObjectStorage storage = storage(REQUEST_TIMEOUT);
        long started = System.nanoTime();
        assertThatThrownBy(() -> storage.put(OBJECT, new byte[]{1}, "text/plain"))
                .isInstanceOf(StorageException.class)
                .hasMessage("对象存储不可用").hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty())
                .satisfies(error -> assertThat(((StorageException) error).kind())
                        .isEqualTo(StorageException.Kind.BUCKET));
        assertCompletesWithin(started, 7);
        assertThat(requestCount("HEAD", "/" + BUCKET)).isEqualTo(1);
        assertThat(requestCount("PUT", "/" + BUCKET)).isEqualTo(1);
        assertThat(requestCount("PUT", objectPath())).isZero();
    }

    @Test
    void continuousObjectBodyStillEndsAtCallTimeout() throws Exception {
        blockedBody = new CountDownLatch(1);
        slowTarget = SlowTarget.DRIP;
        MinioObjectStorage storage = storage(REQUEST_TIMEOUT);

        long started = System.nanoTime();
        try (InputStream stream = storage.open(OBJECT)) {
            assertThatThrownBy(stream::readAllBytes).isInstanceOf(IOException.class);
        }
        assertCompletesWithin(started, 5);
        assertThat(requestCount("GET", objectPath())).isEqualTo(1);
        assertThat(dripChunks.get()).isGreaterThan(1);
    }

    private MinioObjectStorage storage(Duration timeout) {
        MinioObjectStorage storage = new MinioObjectStorage(properties(timeout));
        clients.add(httpClient(storage));
        return storage;
    }

    private StorageProperties properties(Duration timeout) {
        return new StorageProperties(true, endpoint, "synthetic-access", "synthetic-secret", BUCKET, timeout);
    }

    private static OkHttpClient httpClient(MinioObjectStorage storage) {
        Object client = ReflectionTestUtils.getField(storage, "client");
        Object asyncClient = ReflectionTestUtils.getField(client, "asyncClient");
        return (OkHttpClient) ReflectionTestUtils.getField(asyncClient, "httpClient");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String method = exchange.getRequestMethod();
        try {
            if (query != null && query.contains("location")) {
                reply(exchange, 200, "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>"
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            requests.computeIfAbsent(method + " " + path, ignored -> new AtomicInteger()).incrementAndGet();
            boolean object = path.endsWith("/" + OBJECT);
            boolean bucket = path.equals("/" + BUCKET);
            if ("HEAD".equals(method) && bucket && slowTarget == SlowTarget.BUCKET && blockedHeaders != null) {
                await(blockedHeaders);
                return;
            }
            if ("HEAD".equals(method) && bucket && slowTarget == SlowTarget.MAKE_BUCKET) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if ("PUT".equals(method) && bucket && slowTarget == SlowTarget.MAKE_BUCKET && blockedHeaders != null) {
                await(blockedHeaders);
                return;
            }
            if ("HEAD".equals(method) && object && slowTarget == SlowTarget.STAT && blockedHeaders != null) {
                await(blockedHeaders);
                return;
            }
            if ("PUT".equals(method) && object && slowTarget == SlowTarget.WRITE && blockedHeaders != null) {
                exchange.getRequestBody().readAllBytes();
                await(blockedHeaders);
                return;
            }
            if ("GET".equals(method) && object) {
                if (slowTarget == SlowTarget.OPEN && blockedHeaders != null) {
                    await(blockedHeaders);
                    return;
                }
                byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.getResponseHeaders().set("ETag", "\"offline-etag\"");
                exchange.sendResponseHeaders(200, blockedBody == null ? body.length : 1024);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().flush();
                if (blockedBody != null) {
                    if (slowTarget == SlowTarget.DRIP) {
                        long finish = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                        while (System.nanoTime() < finish && !blockedBody.await(150, TimeUnit.MILLISECONDS)) {
                            exchange.getResponseBody().write(1);
                            exchange.getResponseBody().flush();
                            dripChunks.incrementAndGet();
                        }
                    } else {
                        await(blockedBody);
                    }
                }
                return;
            }
            if ("HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Content-Length", object ? Integer.toString(BODY.length()) : "0");
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.getResponseHeaders().set("Last-Modified", "Wed, 15 Oct 2025 00:00:00 GMT");
                exchange.getResponseHeaders().set("ETag", "\"offline-etag\"");
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            if ("PUT".equals(method)) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("ETag", "\"offline-etag\"");
                reply(exchange, 200, new byte[0]);
                return;
            }
            reply(exchange, 200, new byte[0]);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The client is expected to close a timed-out response.
        } finally {
            exchange.close();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        latch.await(8, TimeUnit.SECONDS);
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    private static void assertCompletesWithin(long started, long seconds) {
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(seconds));
    }

    private int requestCount(String method, String path) {
        AtomicInteger count = requests.get(method + " " + path);
        return count == null ? 0 : count.get();
    }

    private static String objectPath() {
        return "/" + BUCKET + "/" + OBJECT;
    }

    private enum SlowTarget {
        NONE, STAT, OPEN, BUCKET, MAKE_BUCKET, WRITE, DRIP
    }
}
