package com.aiflow.engine.integration.audio;

import java.net.URI;

/**
 * 将协议 bean 产生的请求固定到配置的 loopback endpoint。
 *
 * <p>协议 bean 负责请求/响应格式；这个包装器只负责端点隔离和防止协议实现把
 * 请求发往配置之外的地址。endpoint 未配置时按不支持的协议处理，能力报告为
 * unavailable，且不会调用 delegate 或 transport。</p>
 */
public final class LocalAudioHttpProtocol<Q, S> implements AudioHttpProtocol<Q, S> {

    private final URI endpoint;
    private final AudioHttpProtocol<Q, S> delegate;

    public LocalAudioHttpProtocol(URI endpoint, AudioHttpProtocol<Q, S> delegate) {
        this.endpoint = endpoint;
        this.delegate = delegate;
    }

    @Override
    public Kind kind() {
        if (endpoint == null || delegate == null) {
            return Kind.ASYNCHRONOUS;
        }
        try {
            return delegate.kind();
        } catch (RuntimeException ignored) {
            return Kind.ASYNCHRONOUS;
        }
    }

    @Override
    public AudioHttpRequest encode(Q request) {
        if (endpoint == null || delegate == null) {
            throw new IllegalStateException("local audio sidecar endpoint unavailable");
        }
        AudioHttpRequest original = delegate.encode(request);
        if (original == null) {
            throw new IllegalStateException("local audio sidecar request unavailable");
        }
        return new AudioHttpRequest(endpoint, original.contentType(), original.accept(), original.headers(),
                original.body(), original.retrySafe());
    }

    @Override
    public S decode(AudioHttpResponse response, Q request) {
        return delegate.decode(response, request);
    }
}
