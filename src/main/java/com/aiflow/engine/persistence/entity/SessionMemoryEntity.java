package com.aiflow.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话的滚动摘要（表结构说明见 {@code V7__session_memories.sql} 与 docs/memory-design.md §3.2）。
 *
 * <p>{@code summarizedCount} 是「最老的 N 条已经折进摘要」的**前缀计数**，不是水位 id ——
 * 它同时是条件更新的乐观锁：{@code where summarized_count = :expected} 保证多实例下只有一个赢家。
 */
@Entity
@Table(name = "session_memories")
public class SessionMemoryEntity {

    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "summarized_count", nullable = false)
    private int summarizedCount;

    @Column(name = "summary_chars", nullable = false)
    private int summaryChars;

    @Column(length = 64)
    private String model;

    @Column(nullable = false)
    private int tokens;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 400)
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SessionMemoryEntity() {
    }

    public SessionMemoryEntity(UUID sessionId, Instant now) {
        this.sessionId = sessionId;
        this.summarizedCount = 0;
        this.summaryChars = 0;
        this.tokens = 0;
        this.attempts = 0;
        this.updatedAt = now;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getSummary() {
        return summary;
    }

    public int getSummarizedCount() {
        return summarizedCount;
    }

    public int getSummaryChars() {
        return summaryChars;
    }

    public String getModel() {
        return model;
    }

    public int getTokens() {
        return tokens;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
