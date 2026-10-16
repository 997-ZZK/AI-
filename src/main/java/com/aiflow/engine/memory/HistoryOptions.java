package com.aiflow.engine.memory;

import java.util.Locale;

/**
 * 节点级的记忆覆盖（工作流作者可控），解析自节点参数 {@code history} / {@code includeSummary}。
 *
 * <p>为什么给到节点：多数节点该继承会话配置，但**纯函数式工作流**（比如「把这段文本翻译成英文」）
 * 带上历史只会污染输出 —— 这种工作流作者应该能一句话关掉，而不是去改全局配置。
 *
 * @param enabled        是否注入历史（{@code history: none} 关掉）
 * @param limit          条数上限；{@code <0} 表示不注入
 * @param includeSummary 是否注入摘要
 */
public record HistoryOptions(boolean enabled, int limit, boolean includeSummary) {

    public static HistoryOptions inherit(int defaultLimit) {
        return new HistoryOptions(true, defaultLimit, true);
    }

    /**
     * {@code history} = {@code inherit}（默认）| {@code none} | 数字；{@code includeSummary} = true | false。
     * 无法识别的值按 inherit 处理：拼错的参数不该把记忆静默关掉（也不该打开别的东西）。
     */
    public static HistoryOptions parse(String history, String includeSummary, int defaultLimit) {
        String mode = history == null ? "" : history.strip().toLowerCase(Locale.ROOT);
        boolean enabled = !"none".equals(mode) && !"off".equals(mode) && !"false".equals(mode) && !"0".equals(mode);
        int limit = defaultLimit;
        if (!mode.isEmpty() && mode.chars().allMatch(Character::isDigit)) {
            try {
                limit = Integer.parseInt(mode);
            } catch (NumberFormatException ignored) {
                limit = defaultLimit;
            }
        }
        boolean withSummary = !"false".equalsIgnoreCase(includeSummary == null ? "" : includeSummary.strip());
        return new HistoryOptions(enabled, limit, withSummary);
    }
}
