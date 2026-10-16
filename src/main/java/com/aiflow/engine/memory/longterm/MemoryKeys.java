package com.aiflow.engine.memory.longterm;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 记忆 key 的归一化：它决定「同一件事」在库里的落点（unique (user_id, kind, mem_key)）。
 *
 * <p>归一化做四件事：转小写、剥掉「用户 / 我 / 我的」这类主体前缀、把空白与标点统一成连字符、限长。
 * 之所以必须做：模型两次抽取同一件事时，key 很可能是「公司报销规则」与「用户 公司报销规则」——
 * 不归一化就会变成两条并行的事实，用户改一条、另一条还在召回。
 *
 * <p>刻意**不做**同义归并（「报销规则」与「差旅报销流程」是两条）：那需要语义聚类，而判错的代价是
 * 把两件不同的事悄悄合成一件。宁可多留一条，让「可查可删」把它收拾掉。
 */
public final class MemoryKeys {

    /** key 的上限与 user_memories.mem_key 的列宽一致：超长在写入前就截断，不靠数据库报错兜底。 */
    public static final int MAX_KEY_CHARS = 128;

    /**
     * 剥前缀分两档，刻意不对称：
     * <ul>
     *   <li>{@code 我的} / {@code 用户} 直接剥（后面跟什么都行）—— 「我的公司报销规则」与「公司报销规则」
     *       必须落在同一个 key 上，否则用户改一条、另一条还在召回；</li>
     *   <li>{@code 我} / {@code user(s)} / {@code my} 只在**后面跟分隔符**时剥 —— 否则「我们公司的报销规则」
     *       会被剥成「们公司的报销规则」，mythology 会变成 thology。</li>
     * </ul>
     */
    private static final Pattern PREFIX_ALWAYS = Pattern.compile("^(?:我的|用户)");
    private static final Pattern PREFIX_BOUNDARY = Pattern.compile("^(?:我|users?|my)(?=[\\s_\\-—/、:：])");
    private static final Pattern SEPARATORS = Pattern.compile("[_\\-—/、·:：,，。.]+");
    /** 空白单独处理并**整段丢弃**，不折成连字符：「公司 报销规则」与「公司报销规则」必须是同一个 key。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern KEEP = Pattern.compile("[^\\p{IsHan}a-z0-9-]");
    private static final Pattern DASHES = Pattern.compile("-{2,}");

    private MemoryKeys() {
    }

    /** 归一化；输入为空或归一化后为空时返回空串（调用方据此丢弃这条候选）。 */
    public static String normalize(String rawKey) {
        if (rawKey == null) {
            return "";
        }
        String text = rawKey.strip().toLowerCase(Locale.ROOT);
        text = PREFIX_ALWAYS.matcher(text).replaceFirst("");
        text = PREFIX_BOUNDARY.matcher(text).replaceFirst("");
        text = WHITESPACE.matcher(text).replaceAll("");
        text = SEPARATORS.matcher(text).replaceAll("-");
        text = KEEP.matcher(text).replaceAll("");
        text = DASHES.matcher(text).replaceAll("-");
        text = trimDashes(text);
        if (text.length() > MAX_KEY_CHARS) {
            text = trimDashes(text.substring(0, MAX_KEY_CHARS));
        }
        return text;
    }

    private static String trimDashes(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == (char) 45) {
            start++;
        }
        while (end > start && text.charAt(end - 1) == (char) 45) {
            end--;
        }
        return text.substring(start, end);
    }
}
