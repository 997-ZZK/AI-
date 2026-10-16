package com.aiflow.engine.node.builtin;

import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeResult;

import java.util.Map;

/**
 * 额度相关的统一收尾：用尽时给预设话术 + DEGRADED，正常时把用量写进节点输出与运行变量。
 *
 * <p>用量必须落进出参，否则「今天还能不能用云端」只能靠猜；额度拦截必须走 DEGRADED 而不是 FAILED ——
 * 额度用尽是产品行为，不是故障，run 状态里不该显示成一次失败。
 */
final class BudgetBlocks {

    private BudgetBlocks() {
    }

    static NodeResult exhausted(NodeContext context, Map<String, Object> output, TokenBudgetService.Snapshot snapshot,
                                String presetReply) {
        putState(output, snapshot);
        output.put("reply", presetReply);
        output.put("fallback", presetReply);
        output.put("budgetBlocked", true);
        context.setVariable("reply", presetReply);
        context.setVariable("fallback", presetReply);
        context.setVariable("budgetBlocked", true);
        context.setVariable("budgetStatus", snapshot.status().name());
        context.setVariable("budgetUsed", snapshot.used());
        context.setVariable("budgetLimit", snapshot.limit());
        return NodeResult.degraded(output,
                "今日 AI 额度已用尽（" + snapshot.used() + "/" + snapshot.limit() + " token）");
    }

    static void record(NodeContext context, Map<String, Object> output, TokenBudgetService.Snapshot snapshot,
                       long tokens) {
        putState(output, snapshot);
        output.put("tokensUsed", tokens);
        context.setVariable("budgetStatus", snapshot.status().name());
        context.setVariable("budgetUsed", snapshot.used());
        context.setVariable("budgetLimit", snapshot.limit());
        context.setVariable("tokensUsed", tokens);
    }

    private static void putState(Map<String, Object> output, TokenBudgetService.Snapshot snapshot) {
        output.put("budgetStatus", snapshot.status().name());
        output.put("budgetUsed", snapshot.used());
        output.put("budgetLimit", snapshot.limit());
    }
}