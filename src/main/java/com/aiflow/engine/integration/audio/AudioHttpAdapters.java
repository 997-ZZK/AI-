package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AudioProviderException;

/** 适配器共享护栏，不向引擎传播协议解析器的原文异常。 */
final class AudioHttpAdapters {

    private AudioHttpAdapters() {
    }

    static boolean supported(AudioHttpProtocol<?, ?> protocol) {
        try {
            return protocol.kind() == AudioHttpProtocol.Kind.SYNCHRONOUS;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static String metadata(String value, int maxLength) {
        if (value == null || value.length() > maxLength || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException("无效音频 HTTP 适配器配置");
        }
        return value;
    }

    static <Q, S> S call(AudioHttpProtocol<Q, S> protocol, AudioHttpTransport transport, Q request) {
        if (!supported(protocol)) {
            throw new AudioProviderException(AudioProviderException.Reason.UNAVAILABLE);
        }
        checkInterrupted();
        AudioHttpResponse response;
        try {
            response = transport.exchange(protocol.encode(request));
        } catch (AudioProviderException known) {
            throw new AudioProviderException(known.reason());
        } catch (RuntimeException ignored) {
            throw new AudioProviderException(AudioProviderException.Reason.CALL_FAILED);
        }
        checkInterrupted();
        try {
            if (response == null || response.status() != 200) {
                throw new IllegalArgumentException();
            }
            S decoded = protocol.decode(response, request);
            if (decoded == null) {
                throw new IllegalArgumentException();
            }
            return decoded;
        } catch (RuntimeException ignored) {
            throw new AudioProviderException(AudioProviderException.Reason.INVALID_RESPONSE);
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new AudioProviderException(AudioProviderException.Reason.CALL_FAILED);
        }
    }
}
