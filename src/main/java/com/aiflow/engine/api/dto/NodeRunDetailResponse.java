package com.aiflow.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Snapshot bodies are fetched only for the selected attempt. */
public record NodeRunDetailResponse(UUID nodeRunId, String nodeId, String nodeType, int attempt,
                                    String status, Instant startedAt, Instant endedAt, Long elapsedMs,
                                    JsonNode input, JsonNode output, String error) {
}
