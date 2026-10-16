package com.aiflow.engine.tool.audit;

import com.aiflow.engine.tool.ToolKind;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次工具调用的审计记录。
 *
 * <p>为什么单独记一份，而不是只靠 `node_runs` 快照：快照是**工作流视角**的（这个节点干了什么），
 * 审计是**主体视角**的（谁在什么时候用了哪个工具、成没成）。前者回答"这次运行为什么失败"，
 * 后者回答"这个工具上周被谁用过" —— 两个问题不同。
 *
 * <p>`argsHash` 是参数指纹而不是原文：工具参数里常有手机号、token 这类东西，
 * 审计要能定位"是不是同一个调用"，但不该变成第二个敏感数据仓库（与日志里不打印密钥是同一条纪律）。
 *
 * @param at         发生时刻（UTC）
 * @param outcome    结果：SUCCESS / REPLAYED（命中幂等缓存）/ FAILED / DENIED
 * @param userId     发起人（没有用户上下文时为 null，如定时任务）
 * @param sessionId  会话
 * @param runId      运行
 * @param toolId     工具 id
 * @param kind       来源（HTTP / MCP / SKILL）
 * @param detail     结果摘要：失败原因、拒绝原因或返回体摘要
 * @param durationMs 耗时（未真正调用时为 0）
 * @param argsHash   参数指纹（sha256 前 16 位）
 */
public record ToolAuditEvent(
        Instant at,
        String outcome,
        UUID userId,
        UUID sessionId,
        UUID runId,
        String toolId,
        ToolKind kind,
        String detail,
        long durationMs,
        String argsHash
) {

    public static final String SUCCESS = "SUCCESS";
    public static final String REPLAYED = "REPLAYED";
    public static final String FAILED = "FAILED";
    public static final String DENIED = "DENIED";
}
