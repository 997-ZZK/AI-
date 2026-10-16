package com.aiflow.engine.persistence.entity;

import com.aiflow.engine.ingest.DocumentSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class DocumentEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_INDEXED = "INDEXED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    private UUID id;

    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId;

    @Column(nullable = false, length = 300)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private DocumentSourceType sourceType;

    @Column(name = "source_uri", length = 1000)
    private String sourceUri;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false, length = 32)
    private String status = STATUS_PENDING;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(length = 4000)
    private String error;

    @Column(name = "created_by")
    private UUID createdBy;

    /** 对象存储里的原件键（V5）。null = 未留存：能力未开启，或留存失败（失败会记 WARN）。 */
    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "object_bytes")
    private Long objectBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentEntity() {
    }

    public DocumentEntity(UUID id, String kbId, String title, DocumentSourceType sourceType, String sourceUri,
                          String contentHash, UUID createdBy, Instant now) {
        this.id = id;
        this.kbId = kbId;
        this.title = title;
        this.sourceType = sourceType;
        this.sourceUri = sourceUri;
        this.contentHash = contentHash;
        this.createdBy = createdBy;
        this.status = STATUS_PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markIndexed(int chunks, Instant now) {
        this.status = STATUS_INDEXED;
        this.chunkCount = chunks;
        this.error = null;
        this.updatedAt = now;
    }

    /**
     * 记下原件在对象存储里的位置。
     *
     * <p>独立于 markIndexed/markFailed：留存是**旁路**，它的成败与"切片有没有进检索"是两件事。
     */
    public void markObjectStored(String key, long bytes, Instant now) {
        this.objectKey = key;
        this.objectBytes = bytes;
        this.updatedAt = now;
    }

    public void markFailed(String reason, Instant now) {
        this.status = STATUS_FAILED;
        this.error = reason != null && reason.length() > 4000 ? reason.substring(0, 4000) : reason;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getKbId() {
        return kbId;
    }

    public String getTitle() {
        return title;
    }

    public DocumentSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceUri() {
        return sourceUri;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getStatus() {
        return status;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public String getError() {
        return error;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public Long getObjectBytes() {
        return objectBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
