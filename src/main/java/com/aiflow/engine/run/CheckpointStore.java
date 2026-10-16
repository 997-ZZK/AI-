package com.aiflow.engine.run;

import java.util.Optional;
import java.util.UUID;

/**
 * 检查点存取。与 {@link RunRecorder} 分开：记录器管的是「运行/节点发生过什么」（append 事实），
 * 检查点管的是「继续跑需要什么」（可被消费的状态）。
 */
public interface CheckpointStore {

    UUID save(UUID runId, RunCheckpoint checkpoint);

    /** 标记为已消费：恢复成功后调用，重复恢复靠运行的 CAS 拦住，这里只用于排障与回放。 */
    void consume(UUID checkpointId);

    Optional<LoadedCheckpoint> findLatestPending(UUID runId);

    record LoadedCheckpoint(UUID id, UUID runId, RunCheckpoint checkpoint) {
    }
}
