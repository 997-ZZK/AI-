package com.aiflow.engine.api.dto;

/** 同步 ASR 响应；正文仅用于 HTTP 返回，不能被 toString 带入日志。 */
public record AudioTranscriptionResponse(String text, String provider, String model, long durationMs,
                                         AudioLimitsResponse limits) {

    @Override
    public String toString() {
        return "AudioTranscriptionResponse[textChars="
                + (text == null ? 0 : text.codePointCount(0, text.length())) + ", durationMs=" + durationMs + "]";
    }
}
