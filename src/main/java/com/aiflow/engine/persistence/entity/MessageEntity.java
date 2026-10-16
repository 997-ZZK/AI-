package com.aiflow.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class MessageEntity {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(name = "content_type", nullable = false, length = 16)
    private String contentType = "TEXT";

    @Column(length = 65535)
    private String content;

    @Column(name = "client_msg_id", length = 64)
    private String clientMsgId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MessageEntity() {
    }

    public MessageEntity(UUID id, UUID sessionId, UUID userId, String role, String contentType, String content,
                         String clientMsgId, UUID runId, Instant now) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.contentType = contentType;
        this.content = content;
        this.clientMsgId = clientMsgId;
        this.runId = runId;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public String getContentType() {
        return contentType;
    }

    public String getContent() {
        return content;
    }

    public String getClientMsgId() {
        return clientMsgId;
    }

    public UUID getRunId() {
        return runId;
    }

    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
