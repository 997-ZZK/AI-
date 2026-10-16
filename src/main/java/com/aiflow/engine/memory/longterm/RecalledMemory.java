package com.aiflow.engine.memory.longterm;

import java.util.List;
import java.util.UUID;

/** 一条被召回的长期记忆：注入用的最小信息 + 分数（分数要能被人看见，见 preview 接口）。 */
public record RecalledMemory(UUID id, MemoryKind kind, String displayKey, String value, double score) {

    /** 注入成一行文本：{@code - [FACT] 公司报销规则：报销必须附行程单}。 */
    public String render() {
        return "- [" + kind.name() + "] " + displayKey + "：" + value;
    }

    /** 把一批召回结果渲染成注入块（每条一行）。渲染口径只有一处：装配与 preview 共用它。 */
    public static String renderAll(List<RecalledMemory> recalled) {
        if (recalled == null || recalled.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (RecalledMemory memory : recalled) {
            builder.append(memory.render()).append("\n");
        }
        return builder.toString().strip();
    }
}
