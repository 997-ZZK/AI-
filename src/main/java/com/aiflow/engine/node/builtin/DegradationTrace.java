package com.aiflow.engine.node.builtin;

import com.aiflow.engine.provider.AllProvidersFailedException;
import com.aiflow.engine.provider.ProviderRouter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * llm.chat / llm.vision 共用的降级留痕字段：谁答的、探活跳过哪些档、试过哪些档、为什么失败。
 *
 * <p>只写「发生过降级」的字段：没降级的快照保持干净，才可能一眼看出哪一次回答走了兜底。
 */
final class DegradationTrace {

    private DegradationTrace() {
    }

    static Map<String, Object> describe(ProviderRouter.ChatOutcome outcome) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", outcome.providerId());
        output.put("model", outcome.response().model());
        output.put("completionTokens", outcome.response().completionTokens());
        if (!outcome.skippedProviders().isEmpty()) {
            // 探活已判死的档位：这次根本没试，快照里也要留名
            output.put("skippedTiers", outcome.skippedProviders());
        }
        if (!outcome.failedAttempts().isEmpty()) {
            // 降级发生过就写进节点快照：用户看到的是「调用失败」，事故复盘要看到「哪一档先崩的」
            output.put("degradedFrom", outcome.failedAttempts().stream()
                    .map(AllProvidersFailedException.FailedAttempt::providerId).toList());
            output.put("degradedReasons", outcome.failedAttempts().stream()
                    .map(AllProvidersFailedException.FailedAttempt::error).toList());
        }
        return output;
    }
}