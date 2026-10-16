package com.aiflow.engine.run;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 运行检查点：一次挂起（人工审批等外部输入）之后从哪里、以什么状态继续。
 *
 * <p>为什么是「下一个拓扑下标 + 变量快照」而不是「从头重放」：重放会把已执行节点的副作用再来一遍
 * （tool.call 重复出网、llm.chat 重复扣费）。恢复必须从**断点之后**继续，见 docs/approval-node-design.md §2。
 *
 * @param nodeId          挂起的节点（恢复时先按决策结果把它收口，不重新执行）
 * @param nodeRunId       该节点在 node_runs 里那一行（挂起时是 WAITING，恢复时原地收口）
 * @param nextIndex       恢复后从拓扑序的这个下标开始
 * @param activated       挂起那一刻的激活集合（恢复后并入挂起节点的出边判定结果）
 * @param variables       挂起那一刻的变量快照（**不含**挂起节点自己的输出）
 * @param steps           已消耗步数：跨挂起累计，避免「恢复一次白送一份步数预算」
 * @param workflowVersion 定义版本：对不上就拒绝恢复（配置改了不能悄悄按新图跑）
 */
public record RunCheckpoint(String nodeId, UUID nodeRunId, int nextIndex, Set<String> activated,
                            Map<String, Object> variables, int steps, int workflowVersion) {

    public RunCheckpoint {
        activated = activated == null ? Set.of() : Set.copyOf(activated);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
