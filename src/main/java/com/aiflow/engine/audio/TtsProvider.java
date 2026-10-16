package com.aiflow.engine.audio;

/** TTS 能力端口。实现位于 {@code integration.audio}，核心不依赖 HTTP 或 Spring。 */
public interface TtsProvider {

    String id();

    default boolean available() {
        return true;
    }

    default String description() {
        return id();
    }

    TtsResponse synthesize(TtsRequest request);
}
