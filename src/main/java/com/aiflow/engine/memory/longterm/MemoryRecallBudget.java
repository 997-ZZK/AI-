package com.aiflow.engine.memory.longterm;

import java.util.ArrayList;
import java.util.List;

/**
 * 注入字符预算的裁剪（**纯函数**）：按分数从高到低累积，放不下就从最低分那条开始丢。
 *
 * <p>与 M4-1 的窗口裁剪有一处**刻意的不一致**：窗口预算不够时会把单条消息截断，这里不截断，直接丢。
 * 理由是两类内容不同：窗口是逐字对话，截半句仍然可读；记忆是一条**事实**，被切一半会变成另一句话
 * （「报销必须附行程单」截成「报销必须」），宁可不注入。
 *
 * <p>{@code maxChars <= 0} 表示不注入（配置写 0 的意图就是关掉注入，而不是「无限预算」）。
 */
public final class MemoryRecallBudget {

    public record Trimmed(List<RecalledMemory> kept, List<RecalledMemory> dropped) {
    }

    private MemoryRecallBudget() {
    }

    public static Trimmed apply(List<RecalledMemory> sortedDesc, int maxChars) {
        if (sortedDesc == null || sortedDesc.isEmpty()) {
            return new Trimmed(List.of(), List.of());
        }
        if (maxChars <= 0) {
            return new Trimmed(List.of(), List.copyOf(sortedDesc));
        }
        List<RecalledMemory> kept = new ArrayList<>();
        int used = 0;
        int index = 0;
        for (; index < sortedDesc.size(); index++) {
            int cost = sortedDesc.get(index).render().length();
            if (used + cost > maxChars) {
                break;
            }
            kept.add(sortedDesc.get(index));
            used += cost;
        }
        return new Trimmed(List.copyOf(kept), List.copyOf(sortedDesc.subList(index, sortedDesc.size())));
    }
}
