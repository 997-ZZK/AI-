package com.aiflow.engine.graph;

public record RetryPolicy(int maxAttempts, long backoffMillis) {
}
