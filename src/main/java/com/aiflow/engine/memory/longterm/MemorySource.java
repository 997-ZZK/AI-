package com.aiflow.engine.memory.longterm;

import java.util.Locale;

/**
 * 条目的来源。手工录入与自动抽取要分得清清楚楚，因为它们的**权限不同**：
 * 人工录入永不被抽取覆盖（用户自己改的，模型不能拿一次低置信的「新说法」把它冲掉）。
 */
public enum MemorySource {

    EXTRACTED,
    MANUAL;

    public static MemorySource parse(String raw) {
        if (raw == null) {
            return EXTRACTED;
        }
        String text = raw.strip().toUpperCase(Locale.ROOT);
        return MANUAL.name().equals(text) ? MANUAL : EXTRACTED;
    }
}
