package com.aiflow.engine.provider;

import java.util.List;

public record ChatRequest(String model, List<ChatMessage> messages) {

    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
