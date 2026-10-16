package com.aiflow.engine.integration;

import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.retrieval.RetrievalProvider;
import com.aiflow.engine.retrieval.RetrievalQuery;
import com.aiflow.engine.retrieval.RetrievedChunk;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

import static com.aiflow.engine.integration.CloudKnowledgeBaseException.Reason.*;

/**
 * 厂商无关 HTTP 传输，默认自定义 JSON 网关契约；不代表真实厂商已联调。
 * 请求/响应映射交给 KnowledgeBaseProtocol，异常与日志不回显厂商报文。
 */
public class HttpKnowledgeBaseProvider implements RetrievalProvider {

    private final RetrievalProperties.Cloud config;
    private final HttpClient client;
    private final URI endpoint;
    private final KnowledgeBaseProtocol protocol;
    private final Function<String, String> environment;
    private final Semaphore concurrency;

    public HttpKnowledgeBaseProvider(RetrievalProperties.Cloud config) {
        this(config, new JsonKnowledgeBaseProtocol(config.http().mapping()), System::getenv);
    }

    /** 测试可注入仅包含合成凭证的环境读取器，不读取开发机真实环境值。 */
    public HttpKnowledgeBaseProvider(RetrievalProperties.Cloud config, KnowledgeBaseProtocol protocol,
                                     Function<String, String> environment) {
        this.config = config;
        this.protocol = protocol;
        this.environment = environment;
        this.endpoint = validate(config);
        if (protocol == null || environment == null) throw failure(CONFIGURATION, null, false);
        this.concurrency = new Semaphore(config.http().maxConcurrentCalls());
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String source() {
        return config.source();
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        if (Thread.currentThread().isInterrupted()) throw failure(CANCELED, null, false);
        if (!concurrency.tryAcquire()) throw failure(OVERLOADED, null, false);
        try {
            return retrieveWithinDeadline(query, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs()));
        } finally {
            concurrency.release();
        }
    }

    private List<RetrievedChunk> retrieveWithinDeadline(RetrievalQuery query, long deadline) {
        HttpRequest.Builder builder;
        try {
            byte[] bytes = protocol.encode(query, config.datasetId());
            if (bytes == null) throw failure(CONFIGURATION, null, false);
            if (bytes.length > config.http().maxRequestBytes()) throw failure(REQUEST_TOO_LARGE, null, false);
            builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        } catch (CloudKnowledgeBaseException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failure(CONFIGURATION, null, false);
        }
        String variable = config.http().apiKeyEnv();
        if (variable != null && !variable.isBlank()) {
            String credential;
            try {
                credential = environment.apply(variable);
            } catch (RuntimeException ex) {
                throw failure(CONFIGURATION, null, false);
            }
            if (credential == null || credential.isBlank()) throw failure(AUTHENTICATION, null, false);
            if (credential.length() > 8192 || !credential.chars().allMatch(c -> c >= 33 && c <= 126)) {
                throw failure(CONFIGURATION, null, false);
            }
            String scheme = config.http().authScheme();
            try {
                builder.header(config.http().authHeader(), scheme == null || scheme.isBlank() ? credential : scheme + " " + credential);
            } catch (RuntimeException ex) {
                throw failure(CONFIGURATION, null, false);
            }
        }
        HttpRequest request;
        try {
            request = builder.build();
        } catch (RuntimeException ex) {
            throw failure(CONFIGURATION, null, false);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<byte[]> response = send(request, deadline);
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    boolean retryable = (status == 429 || status == 502 || status == 503 || status == 504)
                            && response.headers().firstValue("Retry-After").isEmpty();
                    throw failure(status == 401 || status == 403 ? AUTHENTICATION
                            : status == 429 ? RATE_LIMITED : UPSTREAM, status, retryable);
                }
                try {
                    return protocol.decode(config.source(), response.body());
                } catch (CloudKnowledgeBaseException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    throw failure(INVALID_RESPONSE, null, false);
                }
            } catch (CloudKnowledgeBaseException ex) {
                if (!ex.retryable() || !config.http().retrySafe() || attempt >= config.http().maxAttempts()) throw ex;
                try {
                    if (remainingNanos(deadline) <= TimeUnit.MILLISECONDS.toNanos(25L * attempt)) {
                        throw failure(TIMEOUT, null, true);
                    }
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure(CANCELED, null, false);
                }
            }
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request, long deadline) {
        long remaining = remainingNanos(deadline);
        if (remaining <= 0) throw failure(TIMEOUT, null, true);
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending = client.sendAsync(request, ignored -> new LimitedBodySubscriber(config.http().maxResponseBytes()));
        } catch (RuntimeException ex) {
            throw failure(TRANSPORT, null, false);
        }
        try {
            // 限制整次响应（包括 body），不仅是连接/响应头的等待时间。
            return pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(CANCELED, null, false);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw failure(TIMEOUT, null, true);
        } catch (ExecutionException ex) {
            if (hasCause(ex, BodyLimitExceeded.class)) throw failure(RESPONSE_TOO_LARGE, null, false);
            if (hasCause(ex, HttpTimeoutException.class)) throw failure(TIMEOUT, null, true);
            throw failure(TRANSPORT, null, true);
        }
    }

    private static URI validate(RetrievalProperties.Cloud config) {
        try {
            RetrievalProperties.CloudHttp http = config.http();
            URI base = URI.create(config.baseUrl());
            URI path = URI.create(http.retrievePath());
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                    || base.getRawFragment() != null || !http.retrievePath().startsWith("/")
                    || http.retrievePath().startsWith("//") || path.isAbsolute() || path.getRawQuery() != null
                    || path.getRawFragment() != null || !path.normalize().equals(path)
                    || config.timeoutMs() <= 0 || config.timeoutMs() > 120_000
                    || http.maxResponseBytes() < 1 || http.maxResponseBytes() > 16 * 1024 * 1024
                    || http.maxRequestBytes() < 1 || http.maxRequestBytes() > 16 * 1024 * 1024
                    || http.maxConcurrentCalls() < 1 || http.maxConcurrentCalls() > 64
                    || http.maxAttempts() < 1 || http.maxAttempts() > 3
                    || (http.maxAttempts() > 1 && !http.retrySafe())
                    || !Double.isFinite(config.scoreMin()) || !Double.isFinite(config.scoreMax()) || config.scoreMax() <= config.scoreMin()
                    || http.authHeader() == null || !http.authHeader().matches("[A-Za-z][A-Za-z0-9-]{0,63}")
                    || Set.of("host", "content-length", "connection", "expect", "upgrade", "content-type", "accept")
                        .contains(http.authHeader().toLowerCase(Locale.ROOT))
                    || http.authScheme() == null || !http.authScheme().matches("[A-Za-z0-9_-]{0,32}")
                    || http.apiKeyEnv() == null || (!http.apiKeyEnv().isBlank() && !http.apiKeyEnv().matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
                    || (config.apiKey() != null && !config.apiKey().isBlank() && http.apiKeyEnv().isBlank())) {
                throw failure(CONFIGURATION, null, false);
            }
            return URI.create(config.baseUrl().replaceFirst("/+$", "") + http.retrievePath());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw failure(CONFIGURATION, null, false);
        }
    }

    private static CloudKnowledgeBaseException failure(CloudKnowledgeBaseException.Reason reason,
                                                       Integer status, boolean retryable) {
        return new CloudKnowledgeBaseException(reason, status, retryable);
    }

    private static long remainingNanos(long deadline) { return deadline - System.nanoTime(); }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static final class BodyLimitExceeded extends RuntimeException { }

    /** 在复制 buffer 前校验总字节数，避免服务端大报文先整体分配到堆内存。 */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        private LimitedBodySubscriber(int limit) { this.limit = limit; }

        @Override
        public CompletionStage<byte[]> getBody() { return result; }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer item : items) {
                if (item.remaining() > limit - bytes.size()) {
                    result.completeExceptionally(new BodyLimitExceeded());
                    subscription.cancel();
                    return;
                }
                byte[] part = new byte[item.remaining()];
                item.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) { result.completeExceptionally(throwable); }

        @Override
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
