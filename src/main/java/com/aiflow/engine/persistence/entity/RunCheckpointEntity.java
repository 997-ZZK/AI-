package com.aiflow.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 检查点（表 run_checkpoints）：运行挂起时「从哪儿、以什么状态继续」。
 *
 * <p>一行一次挂起（append）：同一运行挂起两次（两道审批）就有两行，排障时能看出恢复用的是哪一份快照。
 */
@Entity
@Table(name = "run_checkpoints")
public class RunCheckpointEntity {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(nullable = false)
    private int seq;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    public RunCheckpointEntity() {
    }

    public RunCheckpointEntity(UUID id, UUID runId, String nodeId, int seq, JsonNode state, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.nodeId = nodeId;
        this.seq = seq;
        this.state = state;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getSeq() {
        return seq;
    }

    public JsonNode getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }
}
