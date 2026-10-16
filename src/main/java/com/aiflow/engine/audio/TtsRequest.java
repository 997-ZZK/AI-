package com.aiflow.engine.audio;

import java.util.Locale;

/** 一次同步 TTS 请求。 */
public record TtsRequest(String text, String voice, String format, String model) {

    public static final int DEFAULT_MAX_TEXT_CHARS = 2_000;

    public TtsRequest {
        if (text == null || text.isBlank()) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        text = text.trim();
        if (text.codePointCount(0, text.length()) > DEFAULT_MAX_TEXT_CHARS) {
            throw new AudioValidationException(AudioValidationException.Reason.TEXT_LIMIT);
        }
        voice = voice == null || voice.isBlank() ? "default" : voice.trim();
        format = format == null || format.isBlank() ? "wav" : format.trim().toLowerCase(Locale.ROOT);
        model = model == null || model.isBlank() ? null : model.trim();
    }

    @Override
    public String toString() {
        return "TtsRequest[textChars=" + text.codePointCount(0, text.length()) + "]";
    }
}
