package com.aiflow.engine.api;

import com.aiflow.engine.approval.ApprovalDecision;
import com.aiflow.engine.approval.ApprovalPolicy;
import com.aiflow.engine.approval.ApprovalProperties;
import com.aiflow.engine.approval.ApprovalResumeService;
import com.aiflow.engine.approval.ApprovalService;
import com.aiflow.engine.approval.ApprovalStatus;
import com.aiflow.engine.approval.notify.ApprovalNotifyDispatcher;
import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.auth.IdentityContext;
import com.aiflow.engine.common.auth.RequireIdentity;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.persistence.entity.ApprovalEntity;
import com.aiflow.engine.persistence.entity.ApprovalEventEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 审批接口：待办、决策、审批链。
 *
 * <p>**决策人身份**：开了鉴权（`aiflow.auth.enabled=true`）时 `decidedBy` 取自令牌身份，
 * 自报且与身份不一致 = 400；**没开鉴权时**它仍是自报字段，名单校验只防误操作、不是安全边界
 * （见 docs/auth-design.md 与 docs/approval-node-design.md §4）。
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ApprovalResumeService resumeService;
    private final ApprovalProperties properties;
    private final ApprovalNotifyDispatcher notifications;

    public ApprovalController(ApprovalService approvalService, ApprovalResumeService resumeService,
                              ApprovalProperties properties, ApprovalNotifyDispatcher notifications) {
        this.approvalService = approvalService;
        this.resumeService = resumeService;
        this.properties = properties;
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResponse<ApprovalListResponse> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String assignee,
                                                 @RequestParam(defaultValue = "50") int limit) {
        ApprovalStatus filter = parseStatus(status);
        if (filter != ApprovalStatus.PENDING) {
            // 宁可明确报「不支持」也不要返回空列表：空列表在待办场景里等于「没有你要处理的」
            throw new BizException(ErrorCode.BAD_REQUEST, "第一版只提供待办列表（status=PENDING）；"
                    + "已收口的审批请查 /api/v1/approvals/{id} 或 /api/v1/runs/{runId}/approvals");
        }
        List<ApprovalView> views = approvalService.listPending(assignee).stream()
                .map(this::toView).limit(Math.max(1, limit)).toList();
        return ApiResponse.ok(new ApprovalListResponse(views.size(), filter.name(), views, properties.sweepEnabled()));
    }

    /**
     * 通知渠道状态：配了哪些渠道、生效没有、队列水位与计数。
     *
     * <p>「webhook 怎么没收到」先看这里：渠道在 `channels` 里但 `enabled=false`，就是配置或出网白名单没放行；
     * 计数里 `failed` 在涨，就是接收方那边的问题。
     */
    @GetMapping("/notify")
    public ApiResponse<Map<String, Object>> notifyStatus() {
        return ApiResponse.ok(notifications.status());
    }

    @GetMapping("/{approvalId}")
    public ApiResponse<ApprovalDetail> detail(@PathVariable UUID approvalId) {
        ApprovalEntity approval = approvalService.find(approvalId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审批单不存在: " + approvalId));
        List<ApprovalEventView> events = approvalService.events(approvalId).stream()
                .map(ApprovalController::toEventView).toList();
        return ApiResponse.ok(new ApprovalDetail(toView(approval), events));
    }

    @PostMapping("/{approvalId}/decision")
    @RequireIdentity
    public ApiResponse<ApprovalView> decide(@PathVariable UUID approvalId, @RequestBody DecisionRequest request) {
        // 有身份以身份为准（自报不一致 = 400）；没有身份（鉴权关闭 / 本地开发）保持原来的自报语义
        String decidedBy = IdentityContext.resolveActor(request.decidedBy(), "decidedBy");
        if (decidedBy == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "decidedBy 不能为空（谁批的要留痕）");
        }
        ApprovalDecision decision = parseDecision(request.decision());
        ApprovalService.DecisionOutcome outcome = approvalService.decide(approvalId, decision,
                decidedBy, request.comment());
        // 恢复在决策事务之后派发：事务里派发会让恢复线程读到尚未提交的状态
        resumeService.resumeAfterDecision(outcome);
        return ApiResponse.ok(toView(outcome.approval()));
    }

    /** 运行视角的审批链（RunController 复用；对外只有 /api/v1/runs/{runId}/approvals 这一条路径）。 */
    List<ApprovalView> listByRun(UUID runId) {
        return approvalService.listByRun(runId).stream().map(this::toView).toList();
    }

    private ApprovalView toView(ApprovalEntity approval) {
        ApprovalPolicy policy;
        try {
            policy = approvalService.policyOf(approval);
        } catch (IllegalStateException e) {
            policy = null;
        }
        long waitedMs = approval.getDecidedAt() == null ? 0L
                : Math.max(0L, Duration.between(approval.getCreatedAt(), approval.getDecidedAt()).toMillis());
        return new ApprovalView(approval.getId(), approval.getRunId(), approval.getNodeId(), approval.getLevel(),
                approval.getStatus().name(), approval.getTitle(), approval.getDetail(),
                Arrays.stream(approval.getAssignees().split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList(),
                policy == null ? null : policy.onTimeout().name(),
                policy == null ? null : policy.maxLevel(),
                policy == null ? null : policy.onReject().name(),
                approval.getStatus() == ApprovalStatus.PENDING ? null : approval.getDecision() == null
                        ? null : approval.getDecision().name(),
                approval.getDecidedBy(), approval.getDecidedAt(), approval.getComment(),
                approval.getDueAt(), approval.getCreatedAt(), waitedMs);
    }

    private static ApprovalEventView toEventView(ApprovalEventEntity event) {
        return new ApprovalEventView(event.getEvent(), event.getActor(), event.getComment(), event.getCreatedAt());
    }

    private ApprovalStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return ApprovalStatus.PENDING;
        }
        try {
            return ApprovalStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 非法: " + raw
                    + "（PENDING / APPROVED / REJECTED / EXPIRED / CANCELED）");
        }
    }

    private ApprovalDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "decision 不能为空（APPROVE / REJECT）");
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("APPROVE") && !normalized.startsWith("REJECT")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "decision 非法: " + raw + "（APPROVE / REJECT）");
        }
        return normalized.startsWith("APPROVE") ? ApprovalDecision.APPROVE : ApprovalDecision.REJECT;
    }

    public record DecisionRequest(String decision, String decidedBy, String comment) {
    }

    public record ApprovalListResponse(int count, String status, List<ApprovalView> approvals, boolean sweepEnabled) {
    }

    public record ApprovalDetail(ApprovalView approval, List<ApprovalEventView> events) {
    }

    public record ApprovalEventView(String event, String actor, String comment, Instant at) {
    }

    public record ApprovalView(UUID approvalId, UUID runId, String nodeId, int level, String status, String title,
                               String detail, List<String> assignees, String onTimeout, Integer maxLevel,
                               String onReject, String decision, String decidedBy, Instant decidedAt, String comment,
                               Instant dueAt, Instant createdAt, long waitedMs) {
    }
}
