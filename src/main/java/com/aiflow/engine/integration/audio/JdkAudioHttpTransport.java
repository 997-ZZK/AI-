package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AudioProviderException;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 有界同步短音频传输；无凭证查找、日志、重定向或后台探活。 */
public final class JdkAudioHttpTransport implements AudioHttpTransport, AutoCloseable {

    private final Options options;
    private final HttpClient client;
    private final Semaphore permits;

    public JdkAudioHttpTransport(Options options) {
        this.options = Objects.requireNonNull(options);
        this.permits = new Semaphore(options.maxConcurrentCalls());
        this.client = HttpClient.newBuilder().connectTimeout(options.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public AudioHttpResponse exchange(AudioHttpRequest request) {
        if (!permits.tryAcquire()) {
            throw failure(AudioProviderException.Reason.UNAVAILABLE);
        }
        try {
            return exchangeAcquired(request);
        } finally {
            permits.release();
        }
    }

    private AudioHttpResponse exchangeAcquired(AudioHttpRequest request) {
        if (request == null) {
            throw failure(AudioProviderException.Reason.CALL_FAILED);
        }
        byte[] requestBytes = request.body();
        if (requestBytes.length > options.maxRequestBytes()) {
            throw failure(AudioProviderException.Reason.CALL_FAILED);
        }
        long deadline = System.nanoTime() + options.callTimeout().toNanos();
        for (int attempt = 1; ; attempt++) {
            checkInterrupted();
            long remaining = remaining(deadline);
            CompletableFuture<HttpResponse<byte[]>> pending = null;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(request.endpoint())
                        .timeout(Duration.ofNanos(remaining))
                        .header("Content-Type", request.contentType()).header("Accept", request.accept())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes));
                request.headers().forEach(builder::header);
                pending = client.sendAsync(builder.build(), info -> new LimitedBody(options.maxResponseBytes()));
                HttpResponse<byte[]> response = pending.get(remaining(deadline), TimeUnit.NANOSECONDS);
                checkInterrupted();
                int status = response.statusCode();
                if (status == 200) {
                    return new AudioHttpResponse(status, response.headers().firstValue("Content-Type").orElse(""),
                            response.body());
                }
                if (request.retrySafe() && attempt < options.maxAttempts() && retryable(status)
                        && response.headers().firstValue("Retry-After").isEmpty()) {
                    // A Retry-After header prevents automatic retries until a vendor policy is approved.
                    TimeUnit.NANOSECONDS.sleep(Math.min(options.retryDelay().toNanos(), remaining(deadline)));
                    continue;
                }
                if (status >= 400 && status < 500) {
                    throw failure(AudioProviderException.Reason.REJECTED);
                }
                if (status >= 500) {
                    throw failure(AudioProviderException.Reason.CALL_FAILED);
                }
                throw failure(AudioProviderException.Reason.INVALID_RESPONSE);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw failure(AudioProviderException.Reason.CALL_FAILED);
            } catch (TimeoutException timeout) {
                throw failure(AudioProviderException.Reason.TIMEOUT);
            } catch (ExecutionException failed) {
                throw mappedFailure(failed.getCause());
            } catch (AudioProviderException known) {
                throw failure(known.reason());
            } catch (RuntimeException failed) {
                throw failure(AudioProviderException.Reason.CALL_FAILED);
            } finally {
                if (pending != null && !pending.isDone()) {
                    pending.cancel(true);
                }
            }
        }
    }

    @Override
    public void close() {
        // Closing a stalled body must not wait for the upstream to finish transmitting.
        client.shutdownNow();
    }

    private static long remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw failure(AudioProviderException.Reason.TIMEOUT);
        }
        return nanos;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw failure(AudioProviderException.Reason.CALL_FAILED);
        }
    }

    private static boolean retryable(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private static AudioProviderException mappedFailure(Throwable cause) {
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof AudioProviderException known) {
            return failure(known.reason());
        }
        return failure(cause instanceof HttpTimeoutException
                ? AudioProviderException.Reason.TIMEOUT : AudioProviderException.Reason.CALL_FAILED);
    }

    private static AudioProviderException failure(AudioProviderException.Reason reason) {
        return new AudioProviderException(reason);
    }

    /** 整次调用含重试等待在内最多 60s；安全重试最多三次，默认单次。 */
    public record Options(Duration connectTimeout, Duration callTimeout, int maxRequestBytes,
                          int maxResponseBytes, int maxAttempts, Duration retryDelay, int maxConcurrentCalls) {
        public Options {
            if (!validTimeout(connectTimeout) || !validTimeout(callTimeout) || maxRequestBytes <= 0
                    || maxResponseBytes <= 0 || maxAttempts < 1 || maxAttempts > 3 || retryDelay == null
                    || retryDelay.isNegative() || retryDelay.compareTo(Duration.ofSeconds(1)) > 0
                    || maxConcurrentCalls < 1 || maxConcurrentCalls > 64) {
                throw new IllegalArgumentException("无效音频 HTTP 传输限制");
            }
        }

        public Options(Duration connectTimeout, Duration callTimeout, int maxRequestBytes,
                       int maxResponseBytes, int maxAttempts, Duration retryDelay) {
            this(connectTimeout, callTimeout, maxRequestBytes, maxResponseBytes, maxAttempts, retryDelay, 8);
        }

        public static Options defaults() {
            return new Options(Duration.ofSeconds(2), Duration.ofSeconds(10), 5 * 1024 * 1024,
                    5 * 1024 * 1024, 1, Duration.ZERO);
        }

        private static boolean validTimeout(Duration value) {
            return value != null && !value.isZero() && !value.isNegative()
                    && value.compareTo(Duration.ofSeconds(60)) <= 0;
        }
    }

    /** body 的累计限额在收集阶段生效，不先无界读取后再检查。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBody(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) {
                incoming.cancel();
                return;
            }
            subscription = incoming;
            incoming.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maxBytes - body.size()) {
                    result.completeExceptionally(failure(AudioProviderException.Reason.INVALID_RESPONSE));
                    subscription.cancel();
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                body.writeBytes(part);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable ignored) {
            result.completeExceptionally(mappedFailure(ignored));
        }

        @Override
        public void onComplete() {
            result.complete(body.toByteArray());
        }
    }
}
