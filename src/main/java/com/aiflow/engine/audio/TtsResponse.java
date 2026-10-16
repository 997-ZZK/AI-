package com.aiflow.engine.audio;

import java.util.Arrays;

/** TTS provider 的同步音频响应；字节数组采用防御性复制。 */
public record TtsResponse(byte[] audioBytes, String contentType, String provider, String model,
                          long durationMs) {

    public TtsResponse {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new AudioProviderException(AudioProviderException.Reason.INVALID_RESPONSE);
        }
        audioBytes = Arrays.copyOf(audioBytes, audioBytes.length);
        contentType = contentType == null || contentType.isBlank() ? "audio/wav" : contentType;
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        model = model == null || model.isBlank() ? "unknown" : model;
        if (durationMs < 0) {
            throw new IllegalArgumentException("音频时长不能为负数");
        }
    }

    @Override
    public byte[] audioBytes() {
        return Arrays.copyOf(audioBytes, audioBytes.length);
    }

    @Override
    public String toString() {
        return "TtsResponse[byteLength=" + audioBytes.length + ", durationMs=" + durationMs + "]";
    }
}
