package com.aiflow.engine.tool.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 配置里的 {@code ${VAR}} 占位符解析：让「密钥只存在于环境变量里」这条规则在 MCP 配置上也成立。
 *
 * <p>为什么不直接用 yml 的占位符：MCP 配置有三个来源（引擎 yml、Codex 的 config.toml、Claude 的 .mcp.json），
 * 后两个是别的工具的文件，配置绑定的占位符碰不到它们；而且它们的头部字段本来就写成
 * {@code "Authorization": "Bearer ${TOKEN}"} 这种形式。把解析放在「读配置」这一步，三个来源的行为才一致。
 *
 * <p>语法：{@code ${VAR}}（缺失即报错）与 {@code ${VAR:-default}}（缺失用默认值，{@code ${VAR:-}} 表示空串）。
 * **缺失即报错**是刻意的：静默留空会把「凭证没配」变成一串 401，或者更糟 —— 一次匿名调用。
 */
final class EnvPlaceholders {

    private EnvPlaceholders() {
    }

    static String resolve(String raw, Function<String, String> lookup) {
        if (raw == null || raw.indexOf('$') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int index = 0;
        while (index < raw.length()) {
            char current = raw.charAt(index);
            if (current != '$' || index + 1 >= raw.length() || raw.charAt(index + 1) != '{') {
                out.append(current);
                index++;
                continue;
            }
            int end = raw.indexOf('}', index + 2);
            if (end < 0) {
                throw new McpCallException("配置里的环境变量占位符缺少右花括号（只报变量名，不回显整串，避免密钥进日志）");
            }
            String expression = raw.substring(index + 2, end);
            String name = expression;
            String fallback = null;
            int separator = expression.indexOf(":-");
            if (separator >= 0) {
                name = expression.substring(0, separator);
                fallback = expression.substring(separator + 2);
            }
            name = name.strip();
            if (name.isEmpty()) {
                throw new McpCallException("配置里出现了空的占位符 ${}，无法确定要读哪个环境变量");
            }
            String value = lookup.apply(name);
            if (value == null || value.isEmpty()) {
                if (fallback == null) {
                    throw new McpCallException("环境变量 " + name + " 未设置（配置里只允许写变量名，值必须来自环境）");
                }
                value = fallback;
            }
            out.append(value);
            index = end + 1;
        }
        return out.toString();
    }

    static Map<String, String> resolveAll(Map<String, String> raw, Function<String, String> lookup) {
        Map<String, String> resolved = new LinkedHashMap<>();
        raw.forEach((key, value) -> resolved.put(key, resolve(value, lookup)));
        return resolved;
    }
}
