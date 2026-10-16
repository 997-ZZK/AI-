package com.aiflow.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 长期记忆的变更流水（append-only，**不含 value 与 evidence**）。
 *
 * <p>为什么只记 key 不记正文：这张表的用途是回答「这条记忆是怎么来的 / 为什么被拒」，
 * 而不是第二份正文副本。正文只有一处（{@code user_memories.value}），删的时候才删得干净。
 *
 * <p>删除语义是硬删：{@code DELETE} 时同事务删掉该 memory_id 的全部事件行 —— 用户说「删掉」就该删干净，
 * 审计价值让位于隐私（见 docs/long-memory-design.md §3.2）。
 */
@Entity
@Table(name = "user_memory_events")
public class UserMemoryEventEntity {

    @Id
    private UUID id;

    @Column(name = "memory_id", nullable = false)
    private UUID memoryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 16)
    private String action;

    @Column(name = "mem_key", length = 128)
    private String memKey;

    @Column(length = 64)
    private String actor;

    @Column(name = "run_id")
    private UUID runId;

    @Column(length = 300)
    private String detail;

    @Column(nullable = false)
    private Instant at;

    protected UserMemoryEventEntity() {
    }

    public UserMemoryEventEntity(UUID id, UUID memoryId, UUID userId, String action, String memKey, String actor,
                                 UUID runId, String detail, Instant at) {
        this.id = id;
        this.memoryId = memoryId;
        this.userId = userId;
        this.action = action;
        this.memKey = memKey;
        this.actor = actor;
        this.runId = runId;
        this.detail = detail;
        this.at = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMemoryId() {
        return memoryId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public String getMemKey() {
        return memKey;
    }

    public String getActor() {
        return actor;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getAt() {
        return at;
    }
}
