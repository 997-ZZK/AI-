package com.aiflow.engine.approval;

import java.util.List;

/**
 * 审批策略**快照**：请求时刻从节点参数解析一次，之后存在审批行里。
 *
 * <p>两个理由：① 语义上，审批一旦发出，规则就该冻结 —— 改工作流不该改变一个**已经在等**的审批怎么超时；
 * ② 结构上，超时扫描要读这些参数，而读节点参数得走 WorkflowService -> GraphValidator -> NodeRegistry，
 * 那条路依赖执行器、执行器又依赖审批服务，会绕成环。
 *
 * @param onTimeout          到期动作（默认 REJECT）
 * @param maxLevel           最多升到第几级（1 = 不升级）
 * @param onExhausted        升到顶之后的动作（只允许 APPROVE / REJECT）
 * @param escalateAssignees  升级后的审批人（空 = 沿用原班人马）
 * @param onReject           被拒后运行的走向（默认 FALLBACK）
 * @param fallback           被拒后给用户的话术（空 = 用引擎兜底话术）
 * @param waitSeconds        本层决策窗口（秒）
 */
public record ApprovalPolicy(TimeoutAction onTimeout, int maxLevel, TimeoutAction onExhausted,
                             List<String> escalateAssignees, RejectAction onReject, String fallback,
                             int waitSeconds) {

    public ApprovalPolicy {
        onTimeout = onTimeout == null ? TimeoutAction.REJECT : onTimeout;
        maxLevel = Math.max(1, maxLevel);
        onExhausted = onExhausted == null ? TimeoutAction.REJECT : onExhausted;
        escalateAssignees = escalateAssignees == null ? List.of() : List.copyOf(escalateAssignees);
        onReject = onReject == null ? RejectAction.FALLBACK : onReject;
        fallback = fallback == null ? "" : fallback;
        waitSeconds = waitSeconds <= 0 ? 3600 : waitSeconds;
    }
}
