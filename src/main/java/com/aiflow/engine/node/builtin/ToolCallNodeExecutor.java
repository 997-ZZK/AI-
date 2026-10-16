package com.aiflow.engine.node.builtin;

import com.aiflow.engine.common.Hashes;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.TemplateRenderer;
import com.aiflow.engine.tool.HttpToolInvoker;
import com.aiflow.engine.tool.SsrfGuard;
import com.aiflow.engine.tool.ToolBlockedException;
import com.aiflow.engine.tool.ToolInvocationException;
import com.aiflow.engine.tool.ToolInvocationGuard;
import com.aiflow.engine.tool.ToolAuthorizer;
import com.aiflow.engine.tool.ToolKind;
import com.aiflow.engine.tool.ToolRegistry;
import com.aiflow.engine.tool.mcp.McpCallException;
import com.aiflow.engine.tool.mcp.McpServerManager;
import com.aiflow.engine.tool.mcp.McpToolResult;
import com.aiflow.engine.tool.audit.ToolAuditService;
import com.aiflow.engine.tool.skill.SkillCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具节点：调用白名单里的工具（HTTP 出网 / MCP 服务端 / 本机技能库），把结果写进变量供后续节点使用。
 *
 * <p>顺序是有意的：白名单 -> 参数与 URL 拼装 -> 次数/循环检查 -> 幂等命中 -> 出网护栏 -> 真正发请求。
 * 幂等检查排在护栏之后也没关系（命中时根本不发请求），但排在「发请求」之前是硬要求 ——
 * 失败重试是引擎的常规动作，写操作的重复副作用必须在这里被拦住。
 *
 * <p>三个来源共用同一套运行级护栏（次数上限、循环指纹、幂等缓存）：**护栏与来源无关**，
 * 它是「一次运行里工具被怎么用」的属性；而来源决定的是「怎么调用」这一步。
 *
 * <p>工具返回 3xx/4xx/5xx 一律算节点失败（错误里带响应体摘要），交给引擎的重试与 fallback 处理：
 * 把「HTTP 500」当成功会污染下游判断，而业务上真有 4xx 语义的工具应该由工具方约定 2xx + 业务码。
 * MCP 的 {@code isError=true} 同理算失败。
 */
@Component
public class ToolCallNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallNodeExecutor.class);
    private static final int ERROR_SNIPPET_LIMIT = 200;
    private static final int REPLAYED_STATUS = 200;

    private final ToolRegistry registry;
    private final SsrfGuard ssrfGuard;
    private final ToolInvocationGuard invocationGuard;
    private final HttpToolInvoker invoker;
    private final ObjectMapper objectMapper;
    private final McpServerManager mcpServerManager;
    private final SkillCatalog skillCatalog;
    private final ToolAuthorizer authorizer;
    private final ToolAuditService auditService;

    /** 测试与最小装配用：只有 HTTP 工具，且不做授权与审计。 */
    public ToolCallNodeExecutor(ToolRegistry registry, SsrfGuard ssrfGuard, ToolInvocationGuard invocationGuard,
                                HttpToolInvoker invoker, ObjectMapper objectMapper) {
        this(registry, ssrfGuard, invocationGuard, invoker, objectMapper, null, null, null, null);
    }

    @Autowired
    public ToolCallNodeExecutor(ToolRegistry registry, SsrfGuard ssrfGuard, ToolInvocationGuard invocationGuard,
                                HttpToolInvoker invoker, ObjectMapper objectMapper, McpServerManager mcpServerManager,
                                SkillCatalog skillCatalog, ToolAuthorizer authorizer, ToolAuditService auditService) {
        this.registry = registry;
        this.ssrfGuard = ssrfGuard;
        this.invocationGuard = invocationGuard;
        this.invoker = invoker;
        this.objectMapper = objectMapper;
        this.mcpServerManager = mcpServerManager;
        this.skillCatalog = skillCatalog;
        this.authorizer = authorizer;
        this.auditService = auditService;
    }

    @Override
    public String type() {
        return "tool.call";
    }

    /**
     * 入口只做两件事：解析工具 id，和在拿到结果后写一条审计。
     *
     * <p>审计放在这一层而不是各分支里：**任何一次工具调用都应该留痕**，包括"工具没注册"“未授权”
     * 这些还没真正调用的失败。放在分支里迟早会出现"某条新加的提前返回没写审计"。
     */
    @Override
    public NodeResult execute(NodeContext context) {
        long startedAt = System.nanoTime();
        String toolId = TemplateRenderer.render(context.paramString("tool", ""), context.variables()).strip();
        NodeResult result = executeTool(context, toolId);
        recordAudit(context, toolId, result, startedAt);
        return result;
    }

    private NodeResult executeTool(NodeContext context, String toolId) {
        if (toolId.isEmpty()) {
            return NodeResult.failed("节点参数 tool 为空：需要指明要调用的工具 id（见 GET /api/v1/tools 的白名单）");
        }
        ToolRegistry.RegisteredTool tool;
        Map<String, String> args = new LinkedHashMap<>();
        try {
            tool = registry.require(toolId);
            args = renderArgs(context);
        } catch (ToolBlockedException e) {
            return NodeResult.failed(e.getMessage());
        }
        ToolAuthorizer.Decision decision = authorizer == null
                ? new ToolAuthorizer.Decision(true, "no-authorizer", "no-authorizer")
                : authorizer.check(context.userId(), toolId);
        if (!decision.allowed()) {
            // 拒绝用固定前缀，审计据此把它记成 DENIED —— 与"调用失败"是两件事，混在一起会让
            // 「谁被挡在外面」这个问题查不出来
            return NodeResult.failed(ToolAuthorizer.DENIED_PREFIX + decision.reason());
        }
        if (tool.kind() != ToolKind.HTTP) {
            return executeLocalSource(context, tool, args);
        }
        URI uri;
        String body;
        try {
            uri = buildUri(tool, args, context);
            body = buildBody(tool, args, context);
        } catch (JsonProcessingException e) {
            return NodeResult.failed("工具入参序列化失败: " + e.getOriginalMessage());
        } catch (ToolBlockedException e) {
            return NodeResult.failed(e.getMessage());
        }
        String fingerprint = Hashes.sha256Hex(toolId + "|" + tool.method() + "|" + uri + "|" + body);
        String idempotencyKey = resolveIdempotencyKey(context, toolId, fingerprint);
        int callIndex;
        try {
            callIndex = invocationGuard.beforeCall(context.runId().toString(), toolId, fingerprint);
        } catch (ToolBlockedException e) {
            return NodeResult.failed(e.getMessage());
        }
        if (tool.idempotent() && tool.mutating()) {
            ToolInvocationGuard.Cached cached = invocationGuard.lookup(context.runId().toString(), idempotencyKey);
            if (cached != null) {
                log.info("tool {} 幂等命中（run={}），不再发请求", toolId, context.runId());
                return success(context, tool, uri, cached.httpStatus(), cached.body(), cached.truncated(),
                        callIndex, true, 0);
            }
        }
        URI validated;
        try {
            validated = ssrfGuard.validate(uri.toString());
        } catch (ToolBlockedException e) {
            return NodeResult.failed(e.getMessage());
        }
        HttpToolInvoker.Response response;
        try {
            response = invoker.invoke(validated, tool.method(), tool.definition().headers(), body);
        } catch (ToolBlockedException | ToolInvocationException e) {
            return NodeResult.failed(e.getMessage());
        }
        if (!response.successful()) {
            String hint = response.httpStatus() >= 300 && response.httpStatus() < 400
                    ? "（已按策略拒绝跟随重定向）"
                    : "";
            return NodeResult.failed("工具 " + toolId + " 返回 HTTP " + response.httpStatus() + hint
                    + (response.body().isBlank() ? "" : ": " + snippet(response.body())));
        }
        if (tool.idempotent() && tool.mutating()) {
            invocationGuard.remember(context.runId().toString(), idempotencyKey,
                    new ToolInvocationGuard.Cached(response.httpStatus(), response.body(), response.truncated()));
        }
        return success(context, tool, uri, response.httpStatus(), response.body(), response.truncated(),
                callIndex, false, response.durationMs());
    }

    /**
     * MCP 与技能库的分支：同一条护栏链路，只是把「怎么调用」换成非 HTTP 的实现。
     *
     * <p>MCP 工具的语义对引擎是未知的（可能是写操作），所以按「可能写」对待：同一次运行里同指纹的调用
     * 直接回放缓存结果，不重复发给服务端。
     */
    private NodeResult executeLocalSource(NodeContext context, ToolRegistry.RegisteredTool tool,
                                          Map<String, String> args) {
        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            return NodeResult.failed("工具入参序列化失败: " + e.getOriginalMessage());
        }
        String fingerprint = Hashes.sha256Hex(tool.id() + "|" + argsJson);
        int callIndex;
        try {
            callIndex = invocationGuard.beforeCall(context.runId().toString(), tool.id(), fingerprint);
        } catch (ToolBlockedException e) {
            return NodeResult.failed(e.getMessage());
        }
        if (tool.idempotent() && tool.mutating()) {
            ToolInvocationGuard.Cached cached = invocationGuard.lookup(context.runId().toString(), fingerprint);
            if (cached != null) {
                log.info("工具 {} 幂等命中（run={}），不再调用", tool.id(), context.runId());
                return localSuccess(context, tool, cached.body(), callIndex, true, 0);
            }
        }
        long startedAt = System.nanoTime();
        String body;
        try {
            body = switch (tool.kind()) {
                case MCP -> callMcp(tool, args);
                case SKILL -> callSkill(tool, args);
                default -> throw new ToolBlockedException("非 HTTP 工具来源分发失败: " + tool.kind());
            };
        } catch (ToolBlockedException | ToolInvocationException | McpCallException e) {
            return NodeResult.failed(e.getMessage());
        }
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        if (tool.idempotent() && tool.mutating()) {
            invocationGuard.remember(context.runId().toString(), fingerprint,
                    new ToolInvocationGuard.Cached(REPLAYED_STATUS, body, false));
        }
        return localSuccess(context, tool, body, callIndex, false, durationMs);
    }

    private String callMcp(ToolRegistry.RegisteredTool tool, Map<String, String> args) {
        if (mcpServerManager == null || tool.mcp() == null) {
            throw new ToolBlockedException("工具 " + tool.id() + " 需要 MCP 装配，但当前未启用"
                    + "（aiflow.tools.enabled 与 aiflow.tools.mcp.enabled 都要打开）");
        }
        // 调用前比对「本次运行开始时看到的工具快照」与当前快照：热更新改过定义就明确失败，
        // 而不是按旧定义去调一个新工具（细节见 McpServerManager#verifyTool）。
        mcpServerManager.verifyTool(tool.mcp());
        McpToolResult result = mcpServerManager.call(tool.mcp().serverId(), tool.mcp().name(),
                new LinkedHashMap<String, Object>(args));
        if (result.error()) {
            throw new ToolInvocationException("MCP 工具 " + tool.id() + " 返回 isError: " + snippet(result.text()));
        }
        return result.text();
    }

    private String callSkill(ToolRegistry.RegisteredTool tool, Map<String, String> args) {
        if (skillCatalog == null) {
            throw new ToolBlockedException("工具 " + tool.id() + " 需要技能库装配，但当前未启用"
                    + "（aiflow.tools.enabled 与 aiflow.tools.skills.enabled 都要打开）");
        }
        try {
            return switch (tool.skillAction() == null ? "" : tool.skillAction()) {
                case ToolRegistry.SKILL_LIST -> skillCatalog.listJson(args.get("query"), positiveInt(args.get("limit")));
                case ToolRegistry.SKILL_READ -> skillCatalog.readText(args.get("name"),
                        positiveInt(args.get("maxChars")));
                default -> throw new ToolBlockedException("未知技能工具: " + tool.id());
            };
        } catch (IllegalArgumentException e) {
            throw new ToolInvocationException(e.getMessage());
        }
    }

    private NodeResult localSuccess(NodeContext context, ToolRegistry.RegisteredTool tool, String body,
                                    int callIndex, boolean replayed, long durationMs) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("toolId", tool.id());
        output.put("kind", tool.kind().name().toLowerCase(java.util.Locale.ROOT));
        if (tool.mcp() != null) {
            output.put("serverId", tool.mcp().serverId());
            output.put("mcpTool", tool.mcp().name());
        }
        output.put("body", body);
        if (replayed) {
            output.put("replayed", true);
        } else {
            output.put("durationMs", durationMs);
        }
        output.put("callIndex", callIndex);
        String resultVariable = TemplateRenderer.render(context.paramString("resultVariable", "toolResult"),
                context.variables()).strip();
        if (!resultVariable.isEmpty()) {
            context.setVariable(resultVariable, body);
        }
        return NodeResult.success(output);
    }

    /** 写一条审计：参数只留指纹，详情取失败原因或返回体类型。 */
    private void recordAudit(NodeContext context, String toolId, NodeResult result, long startedAtNanos) {
        if (auditService == null || toolId == null || toolId.isEmpty()) {
            return;
        }
        long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
        Map<String, Object> output = result.output() == null ? Map.of() : result.output();
        boolean replayed = Boolean.TRUE.equals(output.get("replayed"));
        String detail = result.error() != null ? result.error()
                : (output.get("httpStatus") == null ? "bodyChars=" + String.valueOf(output.get("body")).length()
                : "httpStatus=" + output.get("httpStatus"));
        // 来源以注册表为准（失败时输出里没有 kind），输出里的 kind 只作为兜底
        ToolKind kind = registry.find(toolId).map(ToolRegistry.RegisteredTool::kind)
                .orElseGet(() -> output.get("kind") == null ? ToolKind.HTTP
                        : ToolKind.valueOf(String.valueOf(output.get("kind")).toUpperCase(java.util.Locale.ROOT)));
        auditService.record(auditService.event(
                ToolAuditService.outcomeOf(result.status() == null ? null : result.status().name(), replayed,
                        result.error()),
                context.userId(), context.sessionId(), context.runId(), toolId, kind, detail, durationMs,
                Hashes.sha256Hex(toolId + "|" + String.valueOf(context.param("args"))).substring(0, 16)));
    }

    private static int positiveInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private NodeResult success(NodeContext context, ToolRegistry.RegisteredTool tool, URI uri, int httpStatus,
                               String body, boolean truncated, int callIndex, boolean replayed, long durationMs) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("toolId", tool.id());
        output.put("kind", "http");
        output.put("method", tool.method());
        output.put("url", uri.toString());
        output.put("httpStatus", httpStatus);
        output.put("body", body);
        if (truncated) {
            output.put("truncated", true);
        }
        if (replayed) {
            output.put("replayed", true);
        }
        output.put("callIndex", callIndex);
        if (!replayed) {
            output.put("durationMs", durationMs);
        }
        String resultVariable = TemplateRenderer.render(context.paramString("resultVariable", "toolResult"),
                context.variables()).strip();
        if (!resultVariable.isEmpty()) {
            context.setVariable(resultVariable, body);
            context.setVariable("toolStatus", httpStatus);
        }
        return NodeResult.success(output);
    }

    /** 入参：params.args 的值可以是模板，渲染后按字符串参与 URL / body / MCP 参数拼装。 */
    private Map<String, String> renderArgs(NodeContext context) {
        Map<String, String> rendered = new LinkedHashMap<>();
        Object raw = context.param("args");
        if (raw instanceof Map<?, ?> mapping) {
            mapping.forEach((key, value) -> rendered.put(String.valueOf(key),
                    TemplateRenderer.render(value == null ? "" : String.valueOf(value), context.variables())));
        }
        return rendered;
    }

    /** URL = 配置模板（可用变量与入参渲染）+ GET/HEAD 的 query 参数。host 在下一步还会再过一次护栏。 */
    private URI buildUri(ToolRegistry.RegisteredTool tool, Map<String, String> args, NodeContext context) {
        Map<String, Object> scope = new LinkedHashMap<>(context.variables());
        scope.putAll(args);
        String url = TemplateRenderer.render(tool.definition().url(), scope);
        if (!args.isEmpty() && ("GET".equals(tool.method()) || "HEAD".equals(tool.method()))) {
            StringBuilder query = new StringBuilder();
            args.forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    query.append(query.isEmpty() ? "" : "&")
                            .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                            .append('=')
                            .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                }
            });
            if (!query.isEmpty()) {
                url = url + (url.contains("?") ? "&" : "?") + query;
            }
        }
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ToolBlockedException("工具 url 无法解析: " + url);
        }
    }

    private String buildBody(ToolRegistry.RegisteredTool tool, Map<String, String> args, NodeContext context)
            throws JsonProcessingException {
        String explicit = TemplateRenderer.render(context.paramString("body", ""), context.variables());
        if (!explicit.isBlank()) {
            return explicit;
        }
        if (!tool.mutating() || args.isEmpty()) {
            return "";
        }
        return objectMapper.writeValueAsString(args);
    }

    private String resolveIdempotencyKey(NodeContext context, String toolId, String fingerprint) {
        String explicit = TemplateRenderer.render(context.paramString("idempotencyKey", ""), context.variables()).strip();
        return explicit.isEmpty() ? fingerprint : Hashes.sha256Hex(toolId + "|" + explicit);
    }

    private static String snippet(String body) {
        String flat = body.strip().replaceAll("\\s+", " ");
        return flat.length() <= ERROR_SNIPPET_LIMIT ? flat : flat.substring(0, ERROR_SNIPPET_LIMIT) + "…";
    }
}
