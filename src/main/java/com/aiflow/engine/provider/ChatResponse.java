package com.aiflow.engine.provider;

public record ChatResponse(String content, String model, int promptTokens, int completionTokens) {
}
