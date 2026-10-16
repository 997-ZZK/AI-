package com.aiflow.engine.node.builtin;

import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.safety.ContentSafety;

import java.util.Map;

/** 输出被审核拦截时的统一处理：换成预设话术、标记 degraded，并让 run 快照留下类别。 */
final class SafetyBlocks {

    private SafetyBlocks() {
    }

    /**
     * 除了换话术，还把 blocked / blockCategory 写进运行变量：
     * 「为什么用户收到的是这句话」必须能从 run 输出里查到，否则只能靠翻日志。
     */
    static NodeResult degraded(NodeContext context, Map<String, Object> output, ContentSafety.Verdict verdict,
                               String presetReply) {
        output.put("reply", presetReply);
        output.put("fallback", presetReply);
        output.put("blocked", true);
        output.put("blockCategory", verdict.category());
        context.setVariable("reply", presetReply);
        context.setVariable("blocked", true);
        context.setVariable("blockCategory", verdict.category());
        return NodeResult.degraded(output, "输出被内容安全审核拦截（" + verdict.category() + "）");
    }
}