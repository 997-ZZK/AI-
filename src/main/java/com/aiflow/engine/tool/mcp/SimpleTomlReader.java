package com.aiflow.engine.tool.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只够用的一小撮 TOML：解析 {@code [table.path]} 与 {@code key = value}。
 *
 * <p>为什么不引一个 TOML 库：这里唯一的使用场景是「把 Codex 的 config.toml 里 mcp_servers 那段读出来」，
 * 需要的语法就是表头 + 字符串 + 字符串数组 + 整数 + 布尔，几百行的通用实现带来的解析差异风险，
 * 比它省下的这 80 行更贵。内联表（{@code {k=v, k2=v2}}）是支持的，因为 Codex 的 HTTP MCP 服务端就用它写请求头
 * （{@code http_headers = { "X-Foo" = "bar" }}）。**明确不支持**：多行字符串（{@code """}）、数组表（{@code [[x]]}）、
 * 日期时间。遇到不认识的语法按「跳过这一行」处理，不抛异常 —— 配置里多了一行注释之外的东西，不该让启动失败。
 */
public final class SimpleTomlReader {

    private SimpleTomlReader() {
    }

    /** @return 表路径 -> 键值。根表用空字符串作 key。 */
    public static Map<String, Map<String, Object>> read(String text) {
        Map<String, Map<String, Object>> tables = new LinkedHashMap<>();
        List<String> lines = text.lines().toList();
        String current = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i)).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]") && !line.startsWith("[[")) {
                current = unquote(line.substring(1, line.length() - 1).strip());
                tables.computeIfAbsent(current, key -> new LinkedHashMap<>());
                continue;
            }
            int eq = indexOfOutsideQuotes(line, '=');
            if (eq < 0) {
                continue;
            }
            String key = unquote(line.substring(0, eq).strip());
            String valueText = line.substring(eq + 1).strip();
            if (valueText.startsWith("[") && !balanced(valueText)) {
                StringBuilder buffer = new StringBuilder(valueText);
                while (i + 1 < lines.size() && !balanced(buffer.toString())) {
                    i++;
                    buffer.append(' ').append(stripComment(lines.get(i)).strip());
                }
                valueText = buffer.toString();
            }
            tables.computeIfAbsent(current, mapKey -> new LinkedHashMap<>()).put(key, parseValue(valueText));
        }
        return tables;
    }

    static Object parseValue(String raw) {
        String value = raw.strip();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return unescape(value.substring(1, value.length() - 1));
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            return parseArray(value.substring(1, value.length() - 1));
        }
        if (value.startsWith("{") && value.endsWith("}")) {
            return parseInlineTable(value.substring(1, value.length() - 1));
        }
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.parseBoolean(value);
        }
        String numeric = value.replace("_", "");
        if (numeric.matches("-?\\d+")) {
            try {
                return Long.parseLong(numeric);
            } catch (NumberFormatException ignored) {
                return numeric;
            }
        }
        return value;
    }

    static Map<String, Object> parseInlineTable(String body) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String entry : splitTopLevel(body)) {
            int eq = indexOfOutsideQuotes(entry, '=');
            if (eq < 0) {
                continue;
            }
            values.put(unquote(entry.substring(0, eq).strip()), parseValue(entry.substring(eq + 1).strip()));
        }
        return values;
    }

    /** 按顶层逗号切分：引号内与嵌套的 [] / {} 里的逗号不算分隔符。 */
    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth <= 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private static List<Object> parseArray(String body) {
        List<Object> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == ',') {
                addIfPresent(values, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfPresent(values, current.toString());
        return values;
    }

    private static void addIfPresent(List<Object> values, String raw) {
        String value = raw.strip();
        if (!value.isEmpty()) {
            values.add(parseValue(value));
        }
    }

    static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int indexOfOutsideQuotes(String line, char target) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == target) {
                return i;
            }
        }
        return -1;
    }

    private static boolean balanced(String value) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
        }
        return depth <= 0;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("\"") && value.endsWith("\"")))) {
            return String.valueOf(parseValue(value));
        }
        return value;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
