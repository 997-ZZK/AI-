package com.aiflow.engine.run;

import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeRunStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface RunRecorder {

    UUID startRun(RunRequest request, WorkflowDefinition definition);

    void finishRun(UUID runId, RunStatus status, Map<String, Object> output, String error, int steps);

    UUID startNodeRun(UUID runId, NodeDef node, int attempt, Map<String, Object> inputSnapshot);

    void finishNodeRun(UUID nodeRunId, NodeRunStatus status, Map<String, Object> output, String error, Instant endedAt);

    /**
     * 运行挂起：状态置 WAITING_APPROVAL，并记下在等哪个节点。
     *
     * <p>不写 finished_at（运行没有结束），也不清 started_at（运行时长口径包含等待时间）。
     */
    void markWaiting(UUID runId, String nodeId);

    /**
     * 抢占恢复权：WAITING_APPROVAL -> RUNNING（条件更新），返回 false 表示别人已经恢复或运行已不在等待。
     * 重复决策 / 决策与超时同时到达时，只有拿到 true 的那一次会真的继续执行。
     */
    boolean claimResume(UUID runId);

    /** 节点挂起：状态置 WAITING，不写 ended_at —— 节点尚未收口（恢复时才收口）。 */
    void suspendNodeRun(UUID nodeRunId, Map<String, Object> output);
}
