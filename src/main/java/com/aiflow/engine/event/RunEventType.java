package com.aiflow.engine.event;

public enum RunEventType {
    RUN_STARTED,
    NODE_STARTED,
    DELTA,
    NODE_SUCCEEDED,
    NODE_DEGRADED,
    NODE_FAILED,
    NODE_RETRY,
    /** 节点把自己挂起等外部输入（第一版是人工审批）：节点尚未收口，node_runs 处于 WAITING。 */
    NODE_SUSPENDED,
    /** 运行整体挂起：不占线程、不占会话锁，等决策或超时。 */
    RUN_WAITING,
    RUN_SUCCEEDED,
    RUN_DEGRADED,
    RUN_FAILED,
    RUN_CANCELED,
    /**
     * 会话摘要推进（由记忆维护发出，不是引擎节点）。
     *
     * <p>尽力而为：摘要通常发生在运行终态之后，SSE 客户端多半已经断开；可靠的观测口径是
     * {@code GET /api/v1/sessions/{id}/memory}。
     */
    MEMORY_SUMMARIZED,
    /**
     * 长期记忆抽取完成（由记忆维护发出，不是引擎节点）。
     *
     * <p>只带**计数与类别分布**，不含正文：事件流会进 SSE 与控制台，而记忆正文属于用户隐私。
     * 可靠的观测口径是 {@code GET /api/v1/users/{id}/memories} 与它的事件接口。
     */
    MEMORY_EXTRACTED,
    /** 审批单已生成（由审批服务发出，不是引擎）。 */
    APPROVAL_REQUESTED,
    APPROVAL_DECIDED,
    APPROVAL_ESCALATED,
    APPROVAL_EXPIRED
}
