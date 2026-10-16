package com.aiflow.engine.persistence.entity;

import com.aiflow.engine.tool.ToolKind;
import com.aiflow.engine.tool.audit.ToolAuditEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计事件的持久化形态（表 tool_audit_events，建表理由见 V4 迁移的注释）。
 *
 * <p>为什么不让 {@link ToolAuditEvent} 自己带 JPA 注解：那个 record 是**领域对象**，
 * 被内存缓冲、结构化日志、查询接口三处共用；给它挂上实体注解等于让「存哪儿」污染「是什么」。
 * 转换收在这一层，两边各自演进（以后换 ClickHouse，动的也只是这里）。
 */
@Entity
@Table(name = "tool_audit_events")
public class ToolAuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "tool_id", nullable = false, length = 128)
    private String toolId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "args_hash", length = 64)
    private String argsHash;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ToolAuditEventEntity() {
    }

    private ToolAuditEventEntity(Instant at, String outcome, UUID userId, UUID sessionId, UUID runId, String toolId,
                                 String kind, long durationMs, String argsHash, String detail, Instant createdAt) {
        this.at = at;
        this.outcome = outcome;
        this.userId = userId;
        this.sessionId = sessionId;
        this.runId = runId;
        this.toolId = toolId;
        this.kind = kind;
        this.durationMs = durationMs;
        this.argsHash = argsHash;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public static ToolAuditEventEntity of(ToolAuditEvent event, Instant createdAt) {
        return new ToolAuditEventEntity(event.at(), event.outcome(), event.userId(), event.sessionId(), event.runId(),
                event.toolId(), event.kind() == null ? ToolKind.HTTP.name() : event.kind().name(),
                event.durationMs(), event.argsHash(), event.detail(), createdAt);
    }

    public ToolAuditEvent toEvent() {
        return new ToolAuditEvent(at, outcome, userId, sessionId, runId, toolId, kindOf(kind), detail, durationMs,
                argsHash);
    }

    /** 未知取值不抛异常：一行脏数据不该让整个查询接口 500，降级成 null 让它以「来源未知」的样子露出来。 */
    private static ToolKind kindOf(String value) {
        try {
            return value == null ? null : ToolKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Long getId() {
        return id;
    }

    public Instant getAt() {
        return at;
    }
}
