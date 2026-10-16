package com.aiflow.engine.tool.audit;

import com.aiflow.engine.config.ToolAuditProperties;
import com.aiflow.engine.tool.ToolAuthorizer;
import com.aiflow.engine.tool.ToolKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 工具调用审计：内存环形缓冲（此刻能查）+ 结构化日志（事实流）+ 落库（跨重启追溯）。
 *
 * <p>三条腿各管一件事，缺一条都不完整：
 * <ul>
 *   <li>**内存缓冲**回答"刚才那次拒绝是策略还是故障"，代价接近零，但重启即清空；</li>
 *   <li>**INFO 日志**（logger 名 `aiflow.audit`）是按时间顺序的事实流，能被采集、能对时间轴；</li>
 *   <li>**落库**（{@link ToolAuditPersistence}）回答"这个工具上周被谁用过"，异步写、失败不阻断调用。
 *       查询优先走库，库不可用时自动回退缓冲，并在结果里标明数据来源 —— 查询者必须知道自己在看哪一份。</li>
 * </ul>
 *
 * <p>缓冲有上限（默认 1000 条）：审计不该成为内存泄漏的来源，淘汰策略就是最朴素的"丢最老的"。
 * 另外参数只记指纹不记原文 —— 工具参数里常有手机号、token 这类东西，审计要能定位"是不是同一个调用"，
 * 但不该变成第二个敏感数据仓库。
 */
@Service
public class ToolAuditService {

    public static final String LOGGER_NAME = "aiflow.audit";

    private static final Logger log = LoggerFactory.getLogger(ToolAuditService.class);
    private static final Logger audit = LoggerFactory.getLogger(LOGGER_NAME);
    private static final int DETAIL_LIMIT = 240;

    private final ToolAuditProperties properties;
    private final ToolAuditPersistence persistence;
    private final Deque<ToolAuditEvent> buffer = new ArrayDeque<>();

    /** 只要内存缓冲与日志（单元测试、或部署里没有持久化组件时）。 */
    public ToolAuditService(ToolAuditProperties properties) {
        this(properties, (ToolAuditPersistence) null);
    }

    @Autowired
    public ToolAuditService(ToolAuditProperties properties, ObjectProvider<ToolAuditPersistence> persistence) {
        this(properties, persistence.getIfAvailable());
    }

    public ToolAuditService(ToolAuditProperties properties, ToolAuditPersistence persistence) {
        this.properties = properties;
        this.persistence = persistence;
    }

    public void record(ToolAuditEvent event) {
        if (event == null || !properties.enabled()) {
            return;
        }
        synchronized (buffer) {
            buffer.addFirst(event);
            while (buffer.size() > properties.maxEntries()) {
                buffer.removeLast();
            }
        }
        audit.info("tool-audit outcome={} tool={} kind={} user={} session={} run={} durationMs={} argsHash={} detail={}",
                event.outcome(), event.toolId(), event.kind(), event.userId(), event.sessionId(), event.runId(),
                event.durationMs(), event.argsHash(), event.detail());
        if (persistence != null) {
            persistence.accept(event);
        }
    }

    /** 最近若干条，可按用户与工具过滤（都为空即全量）—— 只查内存缓冲。 */
    public List<ToolAuditEvent> recent(UUID userId, String toolId, int limit) {
        int capped = Math.max(1, Math.min(limit, Math.max(1, properties.maxEntries())));
        String toolFilter = toolId == null || toolId.isBlank() ? null : toolId.strip();
        List<ToolAuditEvent> result = new ArrayList<>(Math.min(capped, 128));
        synchronized (buffer) {
            for (ToolAuditEvent event : buffer) {
                if (userId != null && !userId.equals(event.userId())) {
                    continue;
                }
                if (toolFilter != null && !toolFilter.equals(event.toolId())) {
                    continue;
                }
                result.add(event);
                if (result.size() >= capped) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 查询审计记录：**优先落库**（跨重启、可按结果过滤），库不可用时回退内存缓冲。
     *
     * @param store database = 数据来自库（跨重启）；memory = 内存缓冲（落库关闭，或库暂时不可用）
     */
    public record RecentAudit(List<ToolAuditEvent> events, String store, long storeTotal) {
    }

    public RecentAudit recent(UUID userId, String toolId, String outcome, int limit) {
        if (persistence != null && persistence.enabled()) {
            try {
                return new RecentAudit(persistence.recent(userId, toolId, outcome, limit), "database",
                        persistence.count());
            } catch (RuntimeException e) {
                log.warn("审计落库查询失败，回退内存缓冲（结果只覆盖本进程启动以来的记录）: {}", e.getMessage());
            }
        }
        return new RecentAudit(recent(userId, toolId, limit), "memory", size());
    }

    public int size() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** 落库是否开启（接口据此告诉调用方「这份结果能回溯多远」）。 */
    public boolean persistent() {
        return persistence != null && persistence.enabled();
    }

    public int pendingWrites() {
        return persistence == null ? 0 : persistence.pending();
    }

    public long droppedWrites() {
        return persistence == null ? 0 : persistence.dropped();
    }

    public long failedWriteBatches() {
        return persistence == null ? 0 : persistence.writeFailures();
    }

    /** 统一构造入口：调用方只说结果，时间戳与截断在这里收口。 */
    public ToolAuditEvent event(String outcome, UUID userId, UUID sessionId, UUID runId, String toolId,
                                ToolKind kind, String detail, long durationMs, String argsHash) {
        return new ToolAuditEvent(Instant.now(), outcome, userId, sessionId, runId, toolId, kind,
                truncate(detail), durationMs, argsHash);
    }

    public static boolean isDenied(String error) {
        return ToolAuthorizer.isDenied(error);
    }

    /** 结果 -> 审计口径。集中在一处，避免各调用点对"什么算失败"各有一套理解。 */
    public static String outcomeOf(String nodeStatus, boolean replayed, String error) {
        if (isDenied(error)) {
            return ToolAuditEvent.DENIED;
        }
        if (replayed) {
            return ToolAuditEvent.REPLAYED;
        }
        return nodeStatus != null && "SUCCEEDED".equals(nodeStatus.toUpperCase(Locale.ROOT))
                ? ToolAuditEvent.SUCCESS : ToolAuditEvent.FAILED;
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        String flat = detail.strip().replaceAll("\\s+", " ");
        return flat.length() <= DETAIL_LIMIT ? flat : flat.substring(0, DETAIL_LIMIT) + "…";
    }
}
