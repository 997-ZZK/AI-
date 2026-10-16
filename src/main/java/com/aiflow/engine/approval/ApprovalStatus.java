package com.aiflow.engine.approval;

/** 审批单状态。EXPIRED 只出现在「升级走了」的层级上：这一层过期了，但没有对运行下决定。 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELED
}
