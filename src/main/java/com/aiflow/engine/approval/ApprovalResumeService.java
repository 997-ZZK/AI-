package com.aiflow.engine.approval;

import com.aiflow.engine.api.service.RunCoordinator;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.config.DispatchProperties;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.persistence.entity.ApprovalEntity;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.session.SessionDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 审批结果 -> 恢复运行。
 *
 * <p>**依赖方向**：本类依赖引擎侧（RunCoordinator -> ExecutionEngine），而 {@link ApprovalService} 不依赖引擎 ——
 * 否则 GraphValidator -> NodeRegistry -> 执行器 -> 审批服务 会绕成环。
 *
 * <p>恢复走 `SessionDispatcher` 重新入队：同一会话的运行必须仍然串行（恢复与用户新发的消息不能同时跑），
 * 也就是「挂起时释放会话锁、恢复时重新取」。释放的那段时间会话是自由的 —— 有意的取舍，
 * 见 docs/approval-node-design.md §2。
 */
@Service
public class ApprovalResumeService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResumeService.class);

    private final ApprovalService approvalService;
    private final RunCoordinator runCoordinator;
    private final SessionDispatcher sessionDispatcher;
    private final WorkflowRunRepository runRepository;
    private final EngineProperties engineProperties;
    private final DispatchProperties dispatchProperties;

    public ApprovalResumeService(ApprovalService approvalService, RunCoordinator runCoordinator,
                                 SessionDispatcher sessionDispatcher, WorkflowRunRepository runRepository,
                                 EngineProperties engineProperties, DispatchProperties dispatchProperties) {
        this.approvalService = approvalService;
        this.runCoordinator = runCoordinator;
        this.sessionDispatcher = sessionDispatcher;
        this.runRepository = runRepository;
        this.engineProperties = engineProperties;
        this.dispatchProperties = dispatchProperties;
    }

    /** 人工决策之后的恢复（调用方已经拿到 CAS 单赢家的结果）。 */
    public void resumeAfterDecision(ApprovalService.DecisionOutcome outcome) {
        dispatch(outcome.approval());
    }

    /**
     * 超时收口之后的恢复。重新按 id 读一次审批行：**策略与决定都在那一行里**（快照），
     * 不重新推导 —— 否则「超时打回」会拿到默认兜底话术，而不是节点上配的那句。
     */
    public void resumeAfterTimeout(ApprovalService.ExpiryOutcome outcome) {
        approvalService.find(outcome.approvalId())
                .ifPresentOrElse(this::dispatch,
                        () -> log.warn("超时收口的审批 {} 读不到了，跳过恢复", outcome.approvalId()));
    }

    private void dispatch(ApprovalEntity approval) {
        if (dispatchProperties.enabled()) {
            // The decision transaction already committed a durable RESUME task.
            return;
        }
        WorkflowRunEntity run = runRepository.findById(approval.getRunId()).orElse(null);
        if (run == null) {
            log.warn("审批 {} 对应的运行 {} 已不存在，跳过恢复", approval.getId(), approval.getRunId());
            return;
        }
        if (run.getSessionId() == null) {
            log.warn("运行 {} 没有会话，无法恢复（审批决策已记录，运行会停在挂起状态）", run.getId());
            return;
        }
        NodeResult nodeResult = toNodeResult(approval);
        UUID runId = run.getId();
        sessionDispatcher.submit(run.getSessionId().toString(), () -> runCoordinator.resume(runId, nodeResult));
    }

    /**
     * 把审批结果映射成「断点节点的结果」。
     *
     * <p>拒绝默认是**降级**（正常业务结果 + 兜底话术），只有 `onReject=fail` 才算失败；
     * 失败时也把 output 带上：审批状态要留在运行的最终快照里，排障时才有据可查。
     */
    public NodeResult toNodeResult(ApprovalEntity approval) {
        ApprovalPolicy policy = approvalService.policyOf(approval);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approvalId", approval.getId().toString());
        output.put("approvalStatus", approval.getStatus().name());
        output.put("approvalNodeId", approval.getNodeId());
        output.put("approvalLevel", approval.getLevel());
        output.put("approvalBy", approval.getDecidedBy() == null ? "" : approval.getDecidedBy());
        output.put("approvalComment", approval.getComment() == null ? "" : approval.getComment());
        output.put("approvalWaitedMs", waitedMs(approval));
        if (approval.getStatus() != ApprovalStatus.REJECTED) {
            return NodeResult.success(output);
        }
        String comment = approval.getComment() == null ? "" : approval.getComment();
        String reason = "审批被拒绝" + (comment.isBlank() ? "" : ": " + comment);
        if (policy.onReject() == RejectAction.FAIL) {
            return new NodeResult(NodeRunStatus.FAILED, output, reason);
        }
        output.put("fallback", policy.fallback().isBlank() ? engineProperties.fallbackReply() : policy.fallback());
        return NodeResult.degraded(output, reason);
    }

    private long waitedMs(ApprovalEntity approval) {
        if (approval.getDecidedAt() == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(approval.getCreatedAt(), approval.getDecidedAt()).toMillis());
    }
}
