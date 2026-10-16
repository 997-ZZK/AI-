package com.aiflow.engine.approval;

/** 被拒绝之后运行怎么走。拒绝是**正常业务结果**，默认按降级处理，而不是当成故障。 */
public enum RejectAction {
    /** 节点 DEGRADED + 走兜底话术，后续节点照常执行（默认）。 */
    FALLBACK,
    /** 节点 FAILED，整条运行失败。 */
    FAIL
}
