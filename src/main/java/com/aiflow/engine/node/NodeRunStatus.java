package com.aiflow.engine.node;

public enum NodeRunStatus {
    RUNNING,
    /** 节点把自己挂起等外部输入（第一版只有审批节点）：尚未收口，ended_at 留空。 */
    WAITING,
    SUCCEEDED,
    DEGRADED,
    FAILED,
    SKIPPED
}
