package com.aiflow.engine.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把本机已有 Agent 的 MCP 配置直接借过来用：Codex 的 {@code config.toml}、Claude 的 {@code .mcp.json} / {@code .claude.json}。
 *
 * <p>为什么做「导入」而不是让用户手抄一遍：这两个文件是同一台机器上真实存在的配置事实，手抄一遍的代价不是一次性的 ——
 * 之后每次在那边加服务端，这边都要记得再抄一次，迟早不同步。导入的语义是「指向它，跟着它走」。
 *
 * <p>三种形态都导入：有 {@code command} 的是 stdio（本地子进程）；有 {@code url} 的按 {@code type} 分 ——
 * 老的 {@code type=sse} 走 SSE 双端点（GET 长连接发现端点 + POST 投递），其余（{@code http} / {@code streamable-http} / 未写）走 Streamable HTTP。
 * 远程形态随后要过 {@code aiflow.tools.allow-hosts} 那套出网白名单，校验发生在 {@code McpServerManager} 装配时 ——
 * 放在那里是因为「导入」只负责读别人的配置，放不放行是引擎自己的决定。
 */
@Component
public class McpConfigImporter {

    private static final Logger log = LoggerFactory.getLogger(McpConfigImporter.class);
    private static final String CODEX_TABLE_PREFIX = "mcp_servers.";

    private final ObjectMapper mapper;

    public McpConfigImporter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<McpServerConfig> load(McpToolProperties properties) {
        List<McpServerConfig> servers = new ArrayList<>();
        for (McpToolProperties.ImportSpec spec : properties.imports()) {
            if (spec.path() == null || spec.path().isBlank()) {
                continue;
            }
            String expanded = expandHome(spec.path().strip());
            Path file = Path.of(expanded);
            if (!Files.isReadable(file)) {
                log.warn("MCP 导入文件不存在或不可读，跳过: {}", expanded);
                continue;
            }
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                log.warn("MCP 导入文件读取失败，跳过: {}（{}）", expanded, e.getMessage());
                continue;
            }
            String prefix = spec.idPrefix() == null ? "" : spec.idPrefix();
            String format = resolveFormat(spec.format(), file);
            List<McpServerConfig> parsed = switch (format) {
                case "codex-toml" -> parseCodexToml(text, expanded, prefix,
                        properties.startupTimeoutMs(), properties.callTimeoutMs());
                case "claude-json" -> parseClaudeJson(text, expanded, prefix,
                        properties.startupTimeoutMs(), properties.callTimeoutMs());
                default -> {
                    log.warn("MCP 导入格式无法识别: {}（可用 auto / codex-toml / claude-json）", format);
                    yield List.of();
                }
            };
            log.info("MCP 导入 {} -> {} 个服务端", expanded, parsed.size());
            servers.addAll(parsed);
        }
        return servers;
    }

    private static String resolveFormat(String format, Path file) {
        String normalized = format == null ? "auto" : format.strip().toLowerCase(java.util.Locale.ROOT);
        if (!"auto".equals(normalized)) {
            return normalized;
        }
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".toml") ? "codex-toml" : "claude-json";
    }

    /** Codex 的 config.toml：{@code [mcp_servers.<id>]} + 可选 {@code [mcp_servers.<id>.env]}。 */
    List<McpServerConfig> parseCodexToml(String text, String origin, String idPrefix, long startupMs, long callMs) {
        Map<String, Map<String, Object>> tables = SimpleTomlReader.read(text);
        List<McpServerConfig> servers = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : tables.entrySet()) {
            String table = entry.getKey();
            if (!table.startsWith(CODEX_TABLE_PREFIX)) {
                continue;
            }
            String rest = table.substring(CODEX_TABLE_PREFIX.length());
            if (rest.isEmpty() || rest.contains(".")) {
                continue;
            }
            Map<String, Object> values = entry.getValue();
            String command = stringValue(values.get("command"));
            String url = stringValue(values.get("url"));
            boolean hasCommand = command != null && !command.isBlank();
            boolean hasUrl = url != null && !url.isBlank();
            if (!hasCommand && !hasUrl) {
                log.info("Codex 配置里的 mcp_servers.{} 既没有 command 也没有 url，跳过", rest);
                continue;
            }
            Long startupOverride = longValue(values.get("startup_timeout_sec"));
            long startupTimeout = startupOverride == null ? startupMs : startupOverride * 1000;
            if (hasUrl) {
                servers.add(McpServerConfig.http(idPrefix + rest, url, stringMap(values.get("http_headers")),
                        stringValue(values.get("bearer_token_env_var")), startupTimeout, callMs, List.of(), origin));
                continue;
            }
            Map<String, String> env = new LinkedHashMap<>();
            Map<String, Object> envTable = tables.get(table + ".env");
            if (envTable != null) {
                envTable.forEach((key, value) -> env.put(key, stringValue(value)));
            }
            servers.add(McpServerConfig.stdio(idPrefix + rest, command, stringList(values.get("args")), env,
                    stringList(values.get("env_vars")), null, startupTimeout, callMs, List.of(), origin));
        }
        return servers;
    }

    /** Claude 的 {@code mcpServers} 对象：{@code {mcpServers: {name: {command, args, env}}}}。 */
    List<McpServerConfig> parseClaudeJson(String text, String origin, String idPrefix, long startupMs, long callMs) {
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (IOException e) {
            log.warn("MCP 导入文件不是合法 JSON，跳过: {}（{}）", origin, e.getMessage());
            return List.of();
        }
        JsonNode serversNode = root.path("mcpServers");
        if (!serversNode.isObject()) {
            log.info("MCP 导入文件里没有 mcpServers 字段: {}", origin);
            return List.of();
        }
        List<McpServerConfig> servers = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = serversNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            JsonNode node = entry.getValue();
            try {
                String command = node.path("command").asText("");
                String url = node.path("url").asText("");
                String type = node.path("type").asText("");
                if (command.isBlank() && url.isBlank()) {
                    log.info("Claude 配置里的 MCP 服务端 {} 既没有 command 也没有 url，跳过", name);
                    continue;
                }
                if (!url.isBlank()) {
                    Map<String, String> headers = new LinkedHashMap<>();
                    JsonNode headersNode = node.path("headers");
                    if (headersNode.isObject()) {
                        headersNode.fields().forEachRemaining(
                                pair -> headers.put(pair.getKey(), pair.getValue().asText("")));
                    }
                    // type=sse 是老式双端点，其余（http / streamable-http / 未写）按单端点处理。
                    servers.add("sse".equalsIgnoreCase(type)
                            ? McpServerConfig.sse(idPrefix + name, url, headers, null, startupMs, callMs,
                                    List.of(), origin)
                            : McpServerConfig.http(idPrefix + name, url, headers, null, startupMs, callMs,
                                    List.of(), origin));
                    continue;
                }
                Map<String, String> env = new LinkedHashMap<>();
                JsonNode envNode = node.path("env");
                if (envNode.isObject()) {
                    envNode.fields().forEachRemaining(pair -> env.put(pair.getKey(), pair.getValue().asText("")));
                }
                List<String> args = new ArrayList<>();
                JsonNode argsNode = node.path("args");
                if (argsNode.isArray()) {
                    argsNode.forEach(item -> args.add(item.asText("")));
                }
                servers.add(McpServerConfig.stdio(idPrefix + name, command, args, env, List.of(), null,
                        startupMs, callMs, List.of(), origin));
            } catch (RuntimeException e) {
                log.warn("Claude 配置里的 MCP 服务端 {} 无法使用，跳过: {}", name, e.getMessage());
            }
        }
        return servers;
    }

    static String expandHome(String path) {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 内联表 / JSON 对象 -> 字符串 map（请求头的值保持占位符原样，解析在连接建立时做）。 */
    private static Map<String, String> stringMap(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> out.put(String.valueOf(key), item == null ? "" : String.valueOf(item)));
        }
        return out;
    }

    private static Long longValue(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof String text && text.matches("-?\\d+")) {
            return Long.parseLong(text);
        }
        return null;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }
}
