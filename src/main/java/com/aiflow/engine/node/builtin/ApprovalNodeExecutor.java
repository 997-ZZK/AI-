package com.aiflow.engine.node.builtin;

import com.aiflow.engine.approval.ApprovalPolicy;
import com.aiflow.engine.approval.ApprovalProperties;
import com.aiflow.engine.approval.ApprovalService;
import com.aiflow.engine.approval.RejectAction;
import com.aiflow.engine.approval.TimeoutAction;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.TemplateRenderer;
import com.aiflow.engine.persistence.entity.ApprovalEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工审批节点（`type: approval`）：落一张待办然后**挂起**运行。
 *
 * <p>执行本身是毫秒级的：节点不等人的（等人的是调度器与决策接口），所以它不被节点执行超时影响，
 * 也不占线程。节点参数见 docs/approval-node-design.md：`assignees` 必填，其余有默认值。
 */
@Component
public class ApprovalNodeExecutor implements NodeExecutor {

    public static final String TYPE = "approval";

    private final ApprovalService approvalService;
    private final ApprovalProperties properties;

    public ApprovalNodeExecutor(ApprovalService approvalService, ApprovalProperties properties) {
        this.approvalService = approvalService;
        this.properties = properties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void validate(NodeDef node, List<String> problems) {
        if (assignees(node).isEmpty()) {
            problems.add("审批节点 " + node.id() + " 必须配 assignees（审批人名单，逗号分隔或数组）");
        }
        if (node.retry() != null && Math.max(1, node.retry().maxAttempts()) > 1) {
            problems.add("审批节点 " + node.id() + " 不能配重试：重试只会多落几张重复待办"
                    + "（唯一索引 (run_id, node_id, level) 是第二道闸）");
        }
        String onTimeout = str(node, "onTimeout", "reject");
        if (parseEnum(TimeoutAction.class, onTimeout) == null) {
            problems.add("审批节点 " + node.id() + " 的 onTimeout 非法: " + onTimeout + "（approve / reject / escalate）");
        }
        String onExhausted = str(node, "onExhausted", "reject");
        if (parseEnum(TimeoutAction.class, onExhausted) == null || parseEnum(TimeoutAction.class, onExhausted) == TimeoutAction.ESCALATE) {
            problems.add("审批节点 " + node.id() + " 的 onExhausted 非法: " + onExhausted
                    + "（只能 approve / reject —— 到顶之后没有更高的级可升）");
        }
        String onReject = str(node, "onReject", "fallback");
        if (parseEnum(RejectAction.class, onReject) == null) {
            problems.add("审批节点 " + node.id() + " 的 onReject 非法: " + onReject + "（fallback / fail）");
        }
        long waitSeconds = waitSeconds(node);
        if (waitSeconds <= 0) {
            problems.add("审批节点 " + node.id() + " 的 waitSeconds 必须为正数");
        } else if (waitSeconds > properties.maxWaitSeconds()) {
            problems.add("审批节点 " + node.id() + " 的 waitSeconds 超过部署上限 " + properties.maxWaitSeconds()
                    + "s（aiflow.approval.max-wait-seconds）");
        }
        if (parseEnum(TimeoutAction.class, onTimeout) == TimeoutAction.ESCALATE && maxLevel(node) < 2) {
            problems.add("审批节点 " + node.id() + " 的 onTimeout=escalate 要求 maxLevel >= 2");
        }
    }

    @Override
    public NodeResult execute(NodeContext context) {
        ApprovalPolicy policy = policy(context.node());
        List<String> assignees = assignees(context.node());
        Instant now = Instant.now();
        Instant dueAt = now.plusSeconds(policy.waitSeconds());
        String title = TemplateRenderer.render(str(context.node(), "title", ""), context.variables()).strip();
        String detail = TemplateRenderer.render(str(context.node(), "detail", ""), context.variables());
        ApprovalEntity approval = approvalService.createRequest(context.runId(), context.node().id(), 1,
                title.isEmpty() ? "需要人工审批" : title, detail, assignees, policy, dueAt);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approvalId", approval.getId().toString());
        output.put("approvalStatus", approval.getStatus().name());
        output.put("approvalLevel", approval.getLevel());
        output.put("approvalAssignees", String.join(",", assignees));
        output.put("approvalDueAt", dueAt.toString());
        return NodeResult.suspend(output);
    }

    private ApprovalPolicy policy(NodeDef node) {
        return new ApprovalPolicy(
                parseEnum(TimeoutAction.class, str(node, "onTimeout", "reject")),
                maxLevel(node),
                parseEnum(TimeoutAction.class, str(node, "onExhausted", "reject")),
                strList(node, "escalateAssignees"),
                parseEnum(RejectAction.class, str(node, "onReject", "fallback")),
                str(node, "fallback", ""),
                (int) waitSeconds(node));
    }

    private int maxLevel(NodeDef node) {
        Object value = node.params().get("maxLevel");
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(text.strip()));
            } catch (NumberFormatException ignored) {
                return properties.defaultMaxLevel();
            }
        }
        return properties.defaultMaxLevel();
    }

    private long waitSeconds(NodeDef node) {
        Object value = node.params().get("waitSeconds");
        long fallback = properties.defaultWaitSeconds();
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** assignees 支持两种写法：CSV 字符串（'a,b'）与数组（便于按环境注入）。 */
    private List<String> assignees(NodeDef node) {
        List<String> values = strList(node, "assignees");
        return values.stream().map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

    private List<String> strList(NodeDef node, String key) {
        Object value = node.params().get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split(","));
    }

    private String str(NodeDef node, String key, String defaultValue) {
        Object value = node.params().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /** 大小写不敏感解析（配置里写 escalate / ESCALATE 都算对）；非法值返回 null 让装配期报错。 */
    private <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
