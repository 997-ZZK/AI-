package com.aiflow.engine.approval.notify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一条审批通知（渠道无关的载荷，序列化后就是给 webhook 的 JSON）。
 *
 * <p>字段是**事发那一刻的快照**：标题、名单、截止时间都取当时的值，接收方不需要回查引擎就能渲染出一张待办卡片。
 * 幂等键 = (approvalId, event, level)，同一个状态变化投两次也能被接收方去掉重。
 *
 * <p>事件按「状态机的一次转移 = 一条通知」划分，没有 ESCALATED：升级在库里本来就是**两行**
 * （旧层 EXPIRED + 新层 PENDING），再单独发一条「升级了」只会让一次超时变成三条卡片。
 *
 * @param event    REQUESTED（落了一张新待办）/ DECIDED（收口，人工或超时策略）/ EXPIRED（超时升级，非终局）/ CANCELED（运行被取消）
 * @param level    该通知对应的审批层级。**EXPIRED 例外**：它记的是升级后的目标层（接收方最关心「升到几级」），
 *                 其余事件就是这张审批单自己的层
 * @param fromLevel 仅 EXPIRED（超时升级）非空：发生超时、被升上来的那一层
 * @param at       事发时刻（不是入队/投递时刻）
 */
public record ApprovalNotification(
        String event,
        UUID approvalId,
        UUID runId,
        String nodeId,
        int level,
        Integer fromLevel,
        String status,
        String title,
        String detail,
        List<String> assignees,
        Instant dueAt,
        String decidedBy,
        String comment,
        Instant decidedAt,
        Instant at) {

    public String dedupeKey() {
        return approvalId + "|" + event + "|" + level;
    }
}
