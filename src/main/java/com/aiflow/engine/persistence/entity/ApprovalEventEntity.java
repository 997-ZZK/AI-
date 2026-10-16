package com.aiflow.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 审批流水（表 approval_events）：追加型，谁在什么时候做了什么都留痕。
 *
 * <p>为什么另开一张表而不是只改 approvals 行：审批是全系统最该追责的动作，而 approvals 一行只留**最终**状态 ——
 * 「谁催过、谁升级的、上一级为什么过期」这些过程信息丢了就再也找不回来。
 */
@Entity
@Table(name = "approval_events")
public class ApprovalEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "approval_id", nullable = false)
    private UUID approvalId;

    @Column(nullable = false, length = 32)
    private String event;

    @Column(length = 128)
    private String actor;

    @Column(length = 2000)
    private String comment;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ApprovalEventEntity() {
    }

    public ApprovalEventEntity(UUID approvalId, String event, String actor, String comment, JsonNode detail,
                               Instant createdAt) {
        this.approvalId = approvalId;
        this.event = event;
        this.actor = actor;
        this.comment = comment;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getApprovalId() {
        return approvalId;
    }

    public String getEvent() {
        return event;
    }

    public String getActor() {
        return actor;
    }

    public String getComment() {
        return comment;
    }

    public JsonNode getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
