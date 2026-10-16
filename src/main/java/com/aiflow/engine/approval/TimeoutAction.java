package com.aiflow.engine.approval;

/** 决策窗口用尽时干什么。 */
public enum TimeoutAction {
    /** 到期即放行（默认不这么做：没人看等于没审，要显式声明才允许）。 */
    APPROVE,
    REJECT,
    ESCALATE
}
