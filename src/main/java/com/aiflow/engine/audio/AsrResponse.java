package com.aiflow.engine.audio;

/** ASR provider 的脱敏同步响应。 */
public record AsrResponse(String text, String provider, String model, long durationMs) {

    public AsrResponse {
        text = text == null ? "" : text;
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        model = model == null || model.isBlank() ? "unknown" : model;
        if (durationMs < 0) {
            throw new IllegalArgumentException("音频时长不能为负数");
        }
    }

    @Override
    public String toString() {
        return "AsrResponse[textChars=" + text.codePointCount(0, text.length()) + ", durationMs=" + durationMs + "]";
    }
}
