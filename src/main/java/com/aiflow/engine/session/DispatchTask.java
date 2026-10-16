package com.aiflow.engine.session;

import java.time.Instant;
import java.util.UUID;

/** A durable execution segment. A suspended run finishes one segment and resumes in another. */
public record DispatchTask(UUID id, UUID sessionId, UUID runId, long seq, DispatchKind kind,
                           UUID checkpointId, UUID approvalId, String dedupeKey, DispatchStatus status,
                           String ownerToken, Instant leaseUntil, Instant cancelRequestedAt,
                           Instant createdAt, Instant updatedAt, Instant startedAt, Instant finishedAt,
                           String lastError) {
}
