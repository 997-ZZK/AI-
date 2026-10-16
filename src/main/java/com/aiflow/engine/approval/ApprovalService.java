package com.aiflow.engine.approval;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.approval.notify.ApprovalNotification;
import com.aiflow.engine.approval.notify.ApprovalNotifyDispatcher;
import com.aiflow.engine.persistence.entity.ApprovalEntity;
import com.aiflow.engine.persistence.entity.ApprovalEventEntity;
import com.aiflow.engine.persistence.repo.ApprovalEventRepository;
import com.aiflow.engine.persistence.repo.ApprovalRepository;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.config.DispatchProperties;
import com.aiflow.engine.session.DispatchTaskStore;
import com.aiflow.engine.run.CheckpointStore;
import com.aiflow.engine.run.RunExecutionRegistry;
import com.aiflow.engine.run.RunStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 审批状态机与持久化。
 *
 * <p>**不依赖执行引擎**（引擎反向依赖执行器、执行器依赖本类）：需要「决策之后继续跑」的动作
 * 由 {@link ApprovalResumeService} 编排，本类只负责把状态改对、把流水记全、把事件发出去。
 *
 * <p>三处并发都靠条件更新（{@code where status = PENDING}）决出单赢家：人工决策、超时扫描、取消。
 * 「先查后写」在单实例上看着没问题，多实例下会双双成功 —— 那样一次审批会触发两次恢复。
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /** 超时/取消这类没有具体人的动作，记这个 actor，查流水时一眼能区分「人来批的」和「系统办的」。 */
    public static final String SYSTEM_ACTOR = "system";

    private final ApprovalRepository approvalRepository;
    private final ApprovalEventRepository approvalEventRepository;
    private final RunEventBus eventBus;
    private final ObjectMapper objectMapper;
    private final ApprovalProperties properties;
    private final ApprovalNotifyDispatcher notifications;
    private final DispatchTaskStore dispatchTasks;
    private final DispatchProperties dispatchProperties;
    private final WorkflowRunRepository runRepository;
    private final CheckpointStore checkpointStore;
    private final RunExecutionRegistry executionRegistry;

    public ApprovalService(ApprovalRepository approvalRepository, ApprovalEventRepository approvalEventRepository,
                           RunEventBus eventBus, ObjectMapper objectMapper, ApprovalProperties properties,
                           ApprovalNotifyDispatcher notifications) {
        this(approvalRepository, approvalEventRepository, eventBus, objectMapper, properties, notifications,
                null, DispatchProperties.disabled(), null, null, new RunExecutionRegistry());
    }

    @Autowired
    public ApprovalService(ApprovalRepository approvalRepository, ApprovalEventRepository approvalEventRepository,
                           RunEventBus eventBus, ObjectMapper objectMapper, ApprovalProperties properties,
                           ApprovalNotifyDispatcher notifications, DispatchTaskStore dispatchTasks,
                           DispatchProperties dispatchProperties, WorkflowRunRepository runRepository,
                           CheckpointStore checkpointStore, RunExecutionRegistry executionRegistry) {
        this.approvalRepository = approvalRepository;
        this.approvalEventRepository = approvalEventRepository;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.notifications = notifications;
        this.dispatchTasks = dispatchTasks;
        this.dispatchProperties = dispatchProperties;
        this.runRepository = runRepository;
        this.checkpointStore = checkpointStore;
        this.executionRegistry = executionRegistry;
    }

    /** 生成待办（审批节点在挂起前调用）。 */
    @Transactional
    public ApprovalEntity createRequest(UUID runId, String nodeId, int level, String title, String detail,
                                        List<String> assignees, ApprovalPolicy policy, Instant dueAt) {
        return executionRegistry.write(runId, () -> createRequestOwned(runId, nodeId, level, title, detail,
                assignees, policy, dueAt));
    }

    private ApprovalEntity createRequestOwned(UUID runId, String nodeId, int level, String title, String detail,
                                             List<String> assignees, ApprovalPolicy policy, Instant dueAt) {
        Instant now = Instant.now();
        ApprovalEntity entity = new ApprovalEntity();
        entity.setId(UUID.randomUUID());
        entity.setRunId(runId);
        entity.setNodeId(nodeId);
        entity.setLevel(level);
        entity.setStatus(ApprovalStatus.PENDING);
        entity.setTitle(title);
        entity.setDetail(detail);
        entity.setAssignees(String.join(",", assignees));
        entity.setPolicy(toJson(policy));
        entity.setDueAt(dueAt);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        approvalRepository.save(entity);
        appendEvent(entity, "REQUESTED", null, null,
                Map.of("level", level, "assignees", assignees, "dueAt", dueAt.toString(),
                        "onTimeout", policy.onTimeout().name(), "maxLevel", policy.maxLevel()));
        publish(RunEventType.APPROVAL_REQUESTED, entity, Map.of(
                "level", level, "title", title == null ? "" : title, "assignees", assignees,
                "dueAt", dueAt.toString(), "onTimeout", policy.onTimeout().name()));
        notifyApproval("REQUESTED", entity, entity.getLevel(), null);
        return entity;
    }

    /** 人工决策：条件更新决出单赢家，输的一方拿到 409 与当前状态（不是静默忽略）。 */
    @Transactional
    public DecisionOutcome decide(UUID approvalId, ApprovalDecision decision, String actor, String comment) {
        ApprovalEntity approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审批单不存在: " + approvalId));
        if (!lockDecisionRun(approval)) {
            throw new BizException(ErrorCode.CONFLICT, "运行尚未挂起或已收口，请重新读取运行状态");
        }
        if (!canDecide(approval, actor)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "审批人不在名单里: " + actor + "（名单: " + approval.getAssignees() + "）");
        }
        ApprovalStatus target = decision == ApprovalDecision.APPROVE
                ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        Instant now = Instant.now();
        int updated = approvalRepository.closeIfPending(approvalId, target, decision, actor, comment, now);
        if (updated == 0) {
            ApprovalEntity current = approvalRepository.findById(approvalId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审批单不存在: " + approvalId));
            throw new BizException(ErrorCode.CONFLICT,
                    "审批单已收口（当前状态: " + current.getStatus() + "），本次决策未生效");
        }
        ApprovalEntity fresh = approvalRepository.findById(approvalId).orElseThrow();
        appendEvent(fresh, target.name(), actor, comment, Map.of("decision", decision.name()));
        publish(RunEventType.APPROVAL_DECIDED, fresh, Map.of(
                "decision", decision.name(), "decidedBy", actor, "comment", comment == null ? "" : comment,
                "waitedMs", waitedMs(fresh)));
        notifyApproval("DECIDED", fresh, fresh.getLevel(), null);
        enqueueFinalDecision(fresh);
        return new DecisionOutcome(fresh, true);
    }

    /**
     * 超时扫描：到期的 PENDING 按策略收口（放行 / 打回 / 升级一层）。
     *
     * <p>升级走「旧层 EXPIRED + 新层 PENDING」两行，审批链留痕；到顶之后按 onExhausted 收尾。
     * 返回结果里 {@code finalDecision()} 为真表示「已经对运行下决定」，调用方据此触发恢复。
     */
    @Transactional
    public List<ExpiryOutcome> expireDue(Instant now) {
        List<ApprovalEntity> due = approvalRepository.findTop50ByStatusAndDueAtBeforeOrderByDueAtAsc(
                ApprovalStatus.PENDING, now);
        if (dispatchProperties.enabled()) {
            // A sweep can touch several sessions. Acquire them in a stable order before any task
            // or run locks, so two instances with overlapping batches cannot reverse that order.
            due.stream().map(ApprovalEntity::getRunId).distinct()
                    .map(runRepository::findSessionIdByRunId).flatMap(Optional::stream).distinct().sorted()
                    .forEach(dispatchTasks::lockSession);
        }
        List<ExpiryOutcome> outcomes = new ArrayList<>();
        for (ApprovalEntity approval : due) {
            if (!lockDecisionRun(approval)) {
                continue;
            }
            try (var administrative = executionRegistry.bindUnguarded(approval.getRunId())) {
                ApprovalPolicy policy = policyOf(approval);
                boolean escalate = policy.onTimeout() == TimeoutAction.ESCALATE && approval.getLevel() < policy.maxLevel();
                if (escalate) {
                    if (approvalRepository.closeIfPending(approval.getId(), ApprovalStatus.EXPIRED, null,
                            SYSTEM_ACTOR, "超时升级", now) == 0) {
                        continue;
                    }
                    ApprovalEntity expired = approvalRepository.findById(approval.getId()).orElseThrow();
                    appendEvent(expired, "EXPIRED", SYSTEM_ACTOR, null,
                            Map.of("action", "ESCALATE", "toLevel", approval.getLevel() + 1));
                    publish(RunEventType.APPROVAL_EXPIRED, expired,
                            Map.of("level", expired.getLevel(), "action", "ESCALATE"));
                    // EXPIRED 的 level 记**目标层**（不是超时那一层），接收方一眼看到「升到几级了」
                    notifyApproval("EXPIRED", expired, expired.getLevel() + 1, expired.getLevel());
                    List<String> nextAssignees = policy.escalateAssignees().isEmpty()
                            ? assigneesOf(expired) : policy.escalateAssignees();
                    ApprovalEntity next = createRequest(expired.getRunId(), expired.getNodeId(),
                            expired.getLevel() + 1, expired.getTitle(), expired.getDetail(), nextAssignees, policy,
                            now.plusSeconds(policy.waitSeconds()));
                    publish(RunEventType.APPROVAL_ESCALATED, next,
                            Map.of("fromLevel", expired.getLevel(), "toLevel", next.getLevel(),
                                    "assignees", assigneesOf(next)));
                    outcomes.add(new ExpiryOutcome(expired.getId(), expired.getRunId(), expired.getNodeId(),
                            expired.getLevel(), ApprovalStatus.EXPIRED, null, true, false));
                    continue;
                }
                // 到顶（或本就 REJECT/APPROVE）：onExhausted 用了 ESCALATE 是配置错误，按 REJECT 处理并记一条 WARN
                TimeoutAction action = policy.onTimeout() == TimeoutAction.ESCALATE
                        ? policy.onExhausted() : policy.onTimeout();
                if (action == TimeoutAction.ESCALATE) {
                    log.warn("approval {} 的 onExhausted 配成了 ESCALATE（无意义），按 REJECT 处理", approval.getId());
                    action = TimeoutAction.REJECT;
                }
                boolean approve = action == TimeoutAction.APPROVE;
                ApprovalStatus target = approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
                ApprovalDecision decision = approve ? ApprovalDecision.APPROVE : ApprovalDecision.REJECT;
                String note = approve ? "超时自动放行" : "超时自动打回";
                if (approvalRepository.closeIfPending(approval.getId(), target, decision, SYSTEM_ACTOR, note, now) == 0) {
                    continue;
                }
                ApprovalEntity fresh = approvalRepository.findById(approval.getId()).orElseThrow();
                appendEvent(fresh, target.name(), SYSTEM_ACTOR, note, Map.of("action", action.name(), "via", "timeout"));
                publish(RunEventType.APPROVAL_EXPIRED, fresh,
                        Map.of("level", fresh.getLevel(), "action", action.name()));
                publish(RunEventType.APPROVAL_DECIDED, fresh,
                        Map.of("decision", decision.name(), "decidedBy", SYSTEM_ACTOR, "via", "timeout",
                                "waitedMs", waitedMs(fresh)));
                notifyApproval("DECIDED", fresh, fresh.getLevel(), null);
                enqueueFinalDecision(fresh);
                outcomes.add(new ExpiryOutcome(fresh.getId(), fresh.getRunId(), fresh.getNodeId(), fresh.getLevel(),
                        target, decision, false, true));
            }
        }
        return outcomes;
    }

    /** 取消一个运行的所有待办（运行被取消时调用）：同样是条件更新，返回真的被取消的条数。 */
    @Transactional
    public int cancelForRun(UUID runId, String actor) {
        List<ApprovalEntity> pending = approvalRepository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(approval -> approval.getStatus() == ApprovalStatus.PENDING)
                .toList();
        if (pending.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int canceled = approvalRepository.cancelPendingForRun(runId, ApprovalStatus.CANCELED, now);
        for (ApprovalEntity approval : pending) {
            // 批量 JPQL 更新不会刷新内存里的实体（clearAutomatically 后它们是游离态），
            // 不显式置状态的话，下面事件与通知里的 status 会停在 PENDING —— 那是在撒谎
            approval.setStatus(ApprovalStatus.CANCELED);
            approval.setUpdatedAt(now);
            appendEvent(approval, "CANCELED", actor, null, Map.of("runId", runId.toString()));
            publish(RunEventType.APPROVAL_EXPIRED, approval, Map.of("level", approval.getLevel(), "action", "CANCELED"));
            notifyApproval("CANCELED", approval, approval.getLevel(), null);
        }
        return canceled;
    }

    public Optional<ApprovalEntity> find(UUID approvalId) {
        return approvalRepository.findById(approvalId);
    }

    public Optional<ApprovalEntity> findPending(UUID runId, String nodeId) {
        return approvalRepository.findFirstByRunIdAndNodeIdAndStatus(runId, nodeId, ApprovalStatus.PENDING);
    }

    /** 待办列表。按人过滤放在内存里做：待办规模天然很小，而把逗号分隔的名单写成 SQL like 会引入子串误匹配。 */
    public List<ApprovalEntity> listPending(String assignee) {
        return approvalRepository.findTop200ByStatusOrderByCreatedAtAsc(ApprovalStatus.PENDING).stream()
                .filter(approval -> assignee == null || assignee.isBlank()
                        || matches(approval.getAssignees(), assignee)
                        || isAdmin(assignee))
                .toList();
    }

    public List<ApprovalEntity> listByRun(UUID runId) {
        return approvalRepository.findByRunIdOrderByCreatedAtAsc(runId);
    }

    public List<ApprovalEventEntity> events(UUID approvalId) {
        return approvalEventRepository.findByApprovalIdOrderByCreatedAtAsc(approvalId);
    }

    public ApprovalPolicy policyOf(ApprovalEntity entity) {
        try {
            return objectMapper.treeToValue(entity.getPolicy(), ApprovalPolicy.class);
        } catch (JsonProcessingException e) {
            // 解析不了不能静默按默认策略走：那会让一个配了「超时放行」的审批悄悄变成「超时打回」
            throw new IllegalStateException("审批策略解析失败: " + entity.getId(), e);
        }
    }

    /**
     * 谁能批：名单里的本人，或部署配置的管理员。名单里写 {@code any} 表示不限。
     *
     * <p>**这不是安全边界**：系统当前无鉴权（M3 遗留），`decidedBy` 由调用方自报，谁都能冒充。
     * 它只是「别让人误批别人单子」的能力约束；真正的鉴权落地时在这里接。
     */
    public boolean canDecide(ApprovalEntity approval, String actor) {
        if (actor == null || actor.isBlank()) {
            return false;
        }
        return isAdmin(actor) || matches(approval.getAssignees(), actor);
    }

    public boolean isAdmin(String actor) {
        return actor != null && Arrays.asList(properties.adminUsers()).contains(actor);
    }

    private boolean matches(String csv, String actor) {
        if (csv == null || actor == null) {
            return false;
        }
        for (String token : csv.split(",")) {
            String trimmed = token.strip();
            if (trimmed.equals(actor) || trimmed.equals("any")) {
                return true;
            }
        }
        return false;
    }

    private List<String> assigneesOf(ApprovalEntity entity) {
        return Arrays.stream(entity.getAssignees().split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private long waitedMs(ApprovalEntity entity) {
        if (entity.getDecidedAt() == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(entity.getCreatedAt(), entity.getDecidedAt()).toMillis());
    }

    private void appendEvent(ApprovalEntity approval, String event, String actor, String comment,
                             Map<String, Object> detail) {
        approvalEventRepository.save(new ApprovalEventEntity(approval.getId(), event, actor, comment,
                toJson(detail), Instant.now()));
    }

    /**
     * 组装并投递一条审批通知。
     *
     * <p>投递在**事务提交之后**才入队（有事务时注册 afterCommit，没有就立刻）：状态机的事务回滚了，
     * 通知就不该存在 —— 否则接收方会收到一条「从来没发生过的待办」，而且它永远不会被撤销。
     *
     * <p>通知失败**不许影响审批**：入队本身是内存操作，异常只记 WARN；真正的投递在
     * {@link ApprovalNotifyDispatcher} 的线程里，失败只计数。
     */
    private void notifyApproval(String event, ApprovalEntity approval, int level, Integer fromLevel) {
        ApprovalNotification payload = new ApprovalNotification(event, approval.getId(), approval.getRunId(),
                approval.getNodeId(), level, fromLevel, approval.getStatus().name(), approval.getTitle(),
                approval.getDetail(), assigneesOf(approval), approval.getDueAt(), approval.getDecidedBy(),
                approval.getComment(), approval.getDecidedAt(), Instant.now());
        Runnable enqueue = () -> {
            try {
                notifications.enqueue(payload);
            } catch (RuntimeException e) {
                log.warn("审批通知入队失败（不影响审批本身）approval={} event={}: {}", approval.getId(), event,
                        e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue.run();
                }
            });
        } else {
            enqueue.run();
        }
    }

    private void publish(RunEventType type, ApprovalEntity approval, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("approvalId", approval.getId().toString());
        payload.put("nodeId", approval.getNodeId());
        payload.put("status", approval.getStatus().name());
        RunEvent event = RunEvent.of(approval.getRunId().toString(), type, approval.getNodeId(), payload);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventBus.publishSafely(event);
                }
            });
        } else {
            eventBus.publishSafely(event);
        }
    }

    private void enqueueFinalDecision(ApprovalEntity approval) {
        if (!dispatchProperties.enabled()) {
            return;
        }
        var run = runRepository.findById(approval.getRunId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审批对应运行不存在"));
        if (run.getStatus() != RunStatus.WAITING_APPROVAL || run.getSessionId() == null) {
            throw new BizException(ErrorCode.CONFLICT, "运行尚未挂起或已收口，请重新读取运行状态");
        }
        var checkpoint = checkpointStore.findLatestPending(run.getId())
                .orElseThrow(() -> new BizException(ErrorCode.CONFLICT, "检查点尚未就绪，请重试"));
        dispatchTasks.enqueueResume(run.getSessionId(), run.getId(), checkpoint.id(), approval.getId());
    }

    private boolean lockDecisionRun(ApprovalEntity approval) {
        if (!dispatchProperties.enabled()) {
            return true;
        }
        UUID runId = approval.getRunId();
        UUID sessionId = runRepository.findSessionIdByRunId(runId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审批对应运行不存在或没有会话"));
        dispatchTasks.lockSession(sessionId);
        dispatchTasks.lockRunTasks(runId);
        var run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审批对应运行不存在"));
        return run.getStatus() == RunStatus.WAITING_APPROVAL
                && checkpointStore.findLatestPending(runId).isPresent();
    }

    private JsonNode toJson(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    public record DecisionOutcome(ApprovalEntity approval, boolean applied) {
    }

    public record ExpiryOutcome(UUID approvalId, UUID runId, String nodeId, int level, ApprovalStatus status,
                                ApprovalDecision decision, boolean escalated, boolean finalDecision) {
    }
}
