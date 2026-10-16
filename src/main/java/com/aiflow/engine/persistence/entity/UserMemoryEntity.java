package com.aiflow.engine.persistence.entity;

import com.aiflow.engine.memory.longterm.MemoryKind;
import com.aiflow.engine.memory.longterm.MemorySource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条长期记忆（表结构与取舍说明见 {@code V8__user_memories.sql} 与 docs/long-memory-design.md §3.2）。
 *
 * <p>{@code embedding} / {@code embedding_model} 里只有 {@code embedding_model} 映射到这里：
 * 向量列由 pgvector 提供，读写全走原生 SQL（{@code PgVectorUserMemoryVectorStore}）——
 * H2 测试库不装扩展也能建表、跑用例（与 {@code DocumentChunkEntity} 同一口径）。
 *
 * <p>{@code (user_id, kind, mem_key)} 上的唯一约束是并发下的单赢家：多实例同时抽取同一个 key 时，
 * 只有一个 INSERT 成功，输的那个按「丢弃」处理（不许两段对话拼成一条事实）。
 */
@Entity
@Table(name = "user_memories")
public class UserMemoryEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemoryKind kind;

    @Column(name = "mem_key", nullable = false, length = 128)
    private String memKey;

    @Column(name = "display_key", nullable = false, length = 200)
    private String displayKey;

    /** 列名是 {@code mem_value}：{@code value} 在 H2 是保留字，而实体映射要在测试库与 PG 上通用。 */
    @Column(name = "mem_value", columnDefinition = "text", nullable = false)
    private String value;

    @Column(nullable = false)
    private double confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemorySource source;

    @Column(name = "source_session")
    private UUID sourceSession;

    @Column(name = "source_run")
    private UUID sourceRun;

    @Column(length = 500)
    private String evidence;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(nullable = false)
    private int hits;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserMemoryEntity() {
    }

    public UserMemoryEntity(UUID id, UUID userId, MemoryKind kind, String memKey, String displayKey, String value,
                            double confidence, MemorySource source, UUID sourceSession, UUID sourceRun,
                            String evidence, Instant now) {
        this.id = id;
        this.userId = userId;
        this.kind = kind;
        this.memKey = memKey;
        this.displayKey = displayKey;
        this.value = value;
        this.confidence = confidence;
        this.source = source;
        this.sourceSession = sourceSession;
        this.sourceRun = sourceRun;
        this.evidence = evidence;
        this.hits = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 抽取覆盖：新说法替换正文，来源与置信度一起更新（display_key 保持用户第一次看到的那份表达）。 */
    public void overwrite(String value, double confidence, String evidence, UUID sourceSession, UUID sourceRun,
                          Instant now) {
        this.value = value;
        this.confidence = confidence;
        this.evidence = evidence;
        this.sourceSession = sourceSession;
        this.sourceRun = sourceRun;
        this.updatedAt = now;
    }

    /** 手工纠正：来源变 MANUAL（此后抽取再也覆盖不了它），置信度恒为 1.00。 */
    public void overwriteByHand(String displayKey, String memKey, String value, Instant now) {
        this.displayKey = displayKey;
        this.memKey = memKey;
        this.value = value;
        this.confidence = 1.0;
        this.source = MemorySource.MANUAL;
        this.evidence = null;
        this.updatedAt = now;
    }

    public void markEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public MemoryKind getKind() {
        return kind;
    }

    public String getMemKey() {
        return memKey;
    }

    public String getDisplayKey() {
        return displayKey;
    }

    public String getValue() {
        return value;
    }

    public double getConfidence() {
        return confidence;
    }

    public MemorySource getSource() {
        return source;
    }

    public UUID getSourceSession() {
        return sourceSession;
    }

    public UUID getSourceRun() {
        return sourceRun;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public int getHits() {
        return hits;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
