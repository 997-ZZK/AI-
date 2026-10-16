package com.aiflow.engine.memory.longterm;

import java.util.Locale;

/**
 * 长期记忆的两类条目。刻意只留两类：
 *
 * <ul>
 *   <li>{@code FACT}：长期成立的事实（环境、项目、约束、术语、流程）；</li>
 *   <li>{@code PREFERENCE}：用户对回答方式的要求（语言、长度、格式、称呼、是否举例）。</li>
 * </ul>
 *
 * <p>不细分更多类别（比如「任务」「日程」）：类别越多，抽取模型越容易左右为难，而召回并不按类别过滤 ——
 * 真正决定能不能召回的是向量与阈值。类别在这里是**给人看的组织方式**（列表接口可按它筛）与提示词的档位。
 */
public enum MemoryKind {

    FACT,
    PREFERENCE;

    /** 解析大小写不敏感的字符串；无法识别返回 null（调用方负责丢弃，不猜）。 */
    public static MemoryKind parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip().toUpperCase(Locale.ROOT);
        for (MemoryKind kind : values()) {
            if (kind.name().equals(text)) {
                return kind;
            }
        }
        return null;
    }
}
