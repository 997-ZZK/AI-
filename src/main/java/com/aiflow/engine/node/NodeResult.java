package com.aiflow.engine.node;

import java.util.Map;

public record NodeResult(NodeRunStatus status, Map<String, Object> output, String error) {

    public static NodeResult success(Map<String, Object> output) {
        return new NodeResult(NodeRunStatus.SUCCEEDED, output == null ? Map.of() : output, null);
    }

    public static NodeResult degraded(Map<String, Object> output, String reason) {
        return new NodeResult(NodeRunStatus.DEGRADED, output == null ? Map.of() : output, reason);
    }

    public static NodeResult failed(String error) {
        return new NodeResult(NodeRunStatus.FAILED, Map.of(), error);
    }

    /**
     * 挂起：节点已经把自己该做的做完了（例如审批单已落库），等的是**外部输入**，不是还没算完。
     *
     * <p>输出里应当带上「在等什么」的凭据（审批节点放 {@code approvalId}）—— 它会被写进 node_runs.output，
     * 恢复时引擎不会重新执行本节点，只把外部输入的结果收口进来。
     */
    public static NodeResult suspend(Map<String, Object> output) {
        return new NodeResult(NodeRunStatus.WAITING, output == null ? Map.of() : output, null);
    }
}
