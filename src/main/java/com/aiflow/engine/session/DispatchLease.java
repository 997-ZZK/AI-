package com.aiflow.engine.session;

import java.util.Objects;
import java.util.UUID;

public record DispatchLease(DispatchTask task, String ownerToken) {
    public DispatchLease {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(ownerToken, "ownerToken");
    }

    public UUID taskId() {
        return task.id();
    }

    public UUID sessionId() {
        return task.sessionId();
    }

    public UUID runId() {
        return task.runId();
    }
}
