package com.aiflow.engine.api.dto;

import java.time.Instant;
import java.util.UUID;

/** One persisted node attempt; elapsedMs includes any wait for human approval. */
public record NodeRunSummaryResponse(UUID nodeRunId, String nodeId, String nodeType, int attempt,
                                     String status, Instant startedAt, Instant endedAt, Long elapsedMs) {
}
