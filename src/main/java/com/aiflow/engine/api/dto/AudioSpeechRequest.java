package com.aiflow.engine.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/** 同步 TTS 请求；model 由通道配置决定，不开放请求透传。 */
public record AudioSpeechRequest(
        @JsonDeserialize(using = AudioStringDeserializer.class) String text,
        @JsonDeserialize(using = AudioStringDeserializer.class) String voice,
        @JsonDeserialize(using = AudioStringDeserializer.class) String format
) {

    @Override
    public String toString() {
        return "AudioSpeechRequest[textChars=" + (text == null ? 0 : text.codePointCount(0, text.length())) + "]";
    }
}
