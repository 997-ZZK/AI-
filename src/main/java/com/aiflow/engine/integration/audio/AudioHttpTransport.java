package com.aiflow.engine.integration.audio;

/** 可以替换为本地协议桩或 JDK HTTP；HTTP 状态失败统一抛固定 AudioProviderException。 */
@FunctionalInterface
public interface AudioHttpTransport {

    AudioHttpResponse exchange(AudioHttpRequest request);
}
