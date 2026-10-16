package com.aiflow.engine.persistence.entity;

import com.aiflow.engine.ingest.ChunkDraft;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 切片表。向量列 embedding 由 pgvector 提供，写入走原生 SQL（见 PgVectorEmbeddingStore），
 * Hibernate 不映射它 —— 这样 H2 测试库无需安装扩展也能跑通整条链路（向量走内存实现）。
 */
@Entity
@Table(name = "document_chunks")
public class DocumentChunkEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;

    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "section_path", length = 500)
    private String sectionPath;

    @Column(name = "page_from")
    private Integer pageFrom;

    @Column(name = "page_to")
    private Integer pageTo;

    @Column(nullable = false, length = 20000)
    private String content;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /**
     * 向量由哪个 embedding provider 生成。写入走 PgVectorEmbeddingStore 的原生 SQL
     * （向量与模型标记必须同一次写，不能分开），这里映射成只读，仅供接口展示与排查。
     */
    @Column(name = "embedding_model", length = 128, insertable = false, updatable = false)
    private String embeddingModel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DocumentChunkEntity() {
    }

    public DocumentChunkEntity(UUID id, DocumentEntity document, String kbId, ChunkDraft draft, Instant now) {
        this.id = id;
        this.document = document;
        this.kbId = kbId;
        this.chunkIndex = draft.index();
        this.sectionPath = draft.sectionPath();
        this.pageFrom = draft.pageFrom();
        this.pageTo = draft.pageTo();
        this.content = draft.content();
        this.charCount = draft.charCount();
        this.contentHash = draft.contentHash();
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public DocumentEntity getDocument() {
        return document;
    }

    public String getKbId() {
        return kbId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getSectionPath() {
        return sectionPath;
    }

    public Integer getPageFrom() {
        return pageFrom;
    }

    public Integer getPageTo() {
        return pageTo;
    }

    public String getContent() {
        return content;
    }

    public int getCharCount() {
        return charCount;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }
}
