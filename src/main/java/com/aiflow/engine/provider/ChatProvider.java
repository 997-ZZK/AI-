package com.aiflow.engine.provider;

import java.util.Set;
import java.util.function.Consumer;

public interface ChatProvider {

    String id();

    Set<String> capabilities();

    default boolean healthy() {
        return true;
    }

    /** 供状态接口展示的简短描述（模型名、端点等），默认空表示无需说明。 */
    default String description() {
        return "";
    }

    ChatResponse chat(ChatRequest request, Consumer<String> deltaConsumer);
}