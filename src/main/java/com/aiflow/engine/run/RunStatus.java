package com.aiflow.engine.run;

/**
 * 运行状态机（docs/architecture.md §6 与 docs/approval-node-design.md §3）。
 *
 * <p>WAITING_APPROVAL 是**非终态**：运行挂起等人工决策，决策（或超时）到达后回到 RUNNING 继续跑，
 * 也可以被取消直接进 CANCELED。它不占线程、不占会话锁 —— 见 docs/approval-node-design.md §2。
 */
public enum RunStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    DEGRADED,
    FAILED,
    CANCELED
}
