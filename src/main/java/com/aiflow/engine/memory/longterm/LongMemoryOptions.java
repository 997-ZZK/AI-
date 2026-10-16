package com.aiflow.engine.memory.longterm;

import java.util.Locale;

/**
 * 节点级的长期记忆开关，解析自节点参数 {@code longMemory}（与 {@code history} 同风格）。
 *
 * <p>为什么给到节点：翻译、改写这类**纯函数式**工作流带上用户偏好只会污染输出 ——
 * 工作流作者应该能一句话关掉。无法识别的值按 inherit 处理：拼错的参数不该静默改变行为。
 */
public record LongMemoryOptions(boolean enabled) {

    public static LongMemoryOptions inherit() {
        return new LongMemoryOptions(true);
    }

    /** {@code inherit}（默认）| {@code none} / {@code off} / {@code false} / {@code 0}。 */
    public static LongMemoryOptions parse(String param) {
        String mode = param == null ? "" : param.strip().toLowerCase(Locale.ROOT);
        boolean on = !"none".equals(mode) && !"off".equals(mode) && !"false".equals(mode) && !"0".equals(mode);
        return new LongMemoryOptions(on);
    }
}
