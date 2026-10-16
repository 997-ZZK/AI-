package com.aiflow.engine.session;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Durable queue and transaction boundary; the core has no dependency on JDBC or JPA. */
public interface DispatchTaskStore {
    /** Requires the caller's acceptance transaction, held through the capacity check and enqueue. */
    void lockSession(UUID sessionId);

    /** Control operations take task locks before touching the run or its approvals. */
    void lockRunTasks(UUID runId);

    DispatchTask enqueueProcess(UUID sessionId, UUID runId);

    DispatchTask enqueueResume(UUID sessionId, UUID runId, UUID checkpointId, UUID approvalId);

    long countOutstanding(UUID sessionId);

    Optional<DispatchLease> claimNext(String ownerToken, Duration ttl);

    Optional<DispatchTask> find(UUID taskId);

    List<DispatchTask> findByRun(UUID runId);

    boolean renew(DispatchLease lease, Duration ttl);

    boolean complete(DispatchLease lease);

    boolean fail(DispatchLease lease, String error);

    /** Only safe before the execution segment has begun; keeps its original sequence position. */
    boolean requeueBeforeExecution(DispatchLease lease);

    List<DispatchTask> requestCancel(UUID runId);

    /** Callback and task cancellation commit in one transaction while the task row stays locked. */
    int cancelQueued(UUID runId, Consumer<DispatchTask> onCanceled);

    /** Callback writes must commit in the same transaction as ownership validation. */
    <T> T guardWrite(DispatchLease lease, Supplier<T> callback);

    /** Expired RUNNING segments fail without replay; the callback closes their run facts atomically. */
    int reapExpired(int batchSize, Consumer<DispatchTask> onExpired);
}
