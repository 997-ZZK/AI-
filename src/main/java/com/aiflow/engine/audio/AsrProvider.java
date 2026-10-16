package com.aiflow.engine.audio;

/** ASR 能力端口。实现位于 {@code integration.audio}，核心不依赖 HTTP 或 Spring。 */
public interface AsrProvider {

    String id();

    default boolean available() {
        return true;
    }

    default String description() {
        return id();
    }

    AsrResponse transcribe(AsrRequest request);
}
