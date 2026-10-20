package com.aiflow.engine.api;

import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.config.ToolPolicyProperties;
import com.aiflow.engine.tool.ToolAuthorizer;
import com.aiflow.engine.tool.ToolRegistry;
import com.aiflow.engine.tool.ToolUrlSummary;
import com.aiflow.engine.tool.audit.ToolAuditEvent;
import com.aiflow.engine.tool.audit.ToolAuditService;
import com.aiflow.engine.tool.mcp.McpServerManager;
import com.aiflow.engine.tool.skill.SkillCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具清单：把「这台机器上的引擎现在到底能调哪些工具」变成一次 HTTP 就能看清的事实。
 *
 * <p>为什么要暴露它：工具注册表是三来源合并的（HTTP / MCP / 技能库），而三来源的装载时机不同 ——
 * HTTP 来自配置、MCP 来自外部服务端进程、技能库来自本机目录扫描。排障时「我配了为什么调不到」
 * 与「它到底发现了什么」是两件事，前者看日志、后者必须有稳定出口，否则每次都要重启加日志。
 *
 * <p>只读，不触发任何副作用（{@code reload} 是显式动作，单独一个 POST）。
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final McpServerManager mcpServerManager;
    private final SkillCatalog skillCatalog;
    private final ToolPolicyProperties policyProperties;
    private final ToolAuthorizer authorizer;
    private final ToolAuditService auditService;

    public ToolController(ToolRegistry toolRegistry, McpServerManager mcpServerManager, SkillCatalog skillCatalog,
                          ToolPolicyProperties policyProperties, ToolAuthorizer authorizer,
                          ToolAuditService auditService) {
        this.toolRegistry = toolRegistry;
        this.mcpServerManager = mcpServerManager;
        this.skillCatalog = skillCatalog;
        this.policyProperties = policyProperties;
        this.authorizer = authorizer;
        this.auditService = auditService;
    }

    /**
     * 授权策略现状：默认放行与否、是否需要用户上下文、规则清单。
     *
     * <p>为什么要把策略读出来：拒绝发生时的第一反应是"我配的规则生效了吗"。跟审计接口放在一起，
     * 「谁被挡了」与「按哪条规则挡的」就能对上。
     */
    @GetMapping("/policy")
    public ApiResponse<Map<String, Object>> policy() {
        List<Map<String, Object>> rules = new ArrayList<>();
        int index = 0;
        for (ToolPolicyProperties.Rule rule : policyProperties.rules()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", index++);
            item.put("tool", rule.tool());
            item.put("allowUsers", rule.allowUsers());
            item.put("denyUsers", rule.denyUsers());
            item.put("reason", rule.reason());
            rules.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("defaultAllow", policyProperties.defaultAllow());
        data.put("requireUser", policyProperties.requireUser());
        data.put("ruleCount", rules.size());
        data.put("rules", rules);
        return ApiResponse.ok(data);
    }

    /**
     * 最近的工具调用审计。默认读**落库**的那份（跨重启可追溯），库不可用时回退内存缓冲 ——
     * 两种来源在 `store` 字段里写明（database / memory），查询者必须知道自己在看哪一份。
     *
     * @param userId  只看某个用户的调用
     * @param toolId  只看某个工具
     * @param outcome 只看某种结果（SUCCESS / REPLAYED / FAILED / DENIED）
     * @param limit   最多返回多少条（默认 50）
     */
    @GetMapping("/audit")
    public ApiResponse<Map<String, Object>> audit(@RequestParam(required = false) UUID userId,
                                                  @RequestParam(required = false) String toolId,
                                                  @RequestParam(required = false) String outcome,
                                                  @RequestParam(defaultValue = "50") int limit) {
        ToolAuditService.RecentAudit audit = auditService.recent(userId, toolId, outcome, limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", auditService.enabled());
        data.put("store", audit.store());
        data.put("persistent", auditService.persistent());
        data.put("storeTotal", audit.storeTotal());
        data.put("bufferSize", auditService.size());
        data.put("pendingWrites", auditService.pendingWrites());
        data.put("droppedWrites", auditService.droppedWrites());
        data.put("failedWriteBatches", auditService.failedWriteBatches());
        data.put("count", audit.events().size());
        data.put("events", audit.events());
        return ApiResponse.ok(data);
    }

    /**
     * 试算某个用户对某个工具的授权结论（不产生调用）。
     *
     * <p>排障时最常问的两个问题是"为什么他调不了"和"这条规则会不会误伤"，两者都要一个不用真跑一次
     * 就能得到答案的入口 —— 真跑一次会产生副作用。
     */
    @GetMapping("/policy/evaluate")
    public ApiResponse<Map<String, Object>> evaluate(@RequestParam String toolId,
                                                     @RequestParam(required = false) UUID userId) {
        ToolAuthorizer.Decision decision = authorizer.check(userId, toolId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolId", toolId);
        data.put("userId", userId);
        data.put("allowed", decision.allowed());
        data.put("rule", decision.rule());
        data.put("reason", decision.reason());
        data.put("registered", toolRegistry.find(toolId).isPresent());
        return ApiResponse.ok(data);
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolRegistry.RegisteredTool tool : toolRegistry.list()) {
            tools.add(describe(tool));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", toolRegistry.enabled());
        data.put("count", tools.size());
        data.put("tools", tools);

        // MCP：服务端清单与失败原因是「外部事实」，和工具本身分开报，避免把「起不来」说成「没配」。
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("enabled", mcpServerManager != null && mcpServerManager.enabled());
        mcp.put("toolCount", mcpServerManager == null ? 0 : mcpServerManager.tools().size());
        mcp.put("failures", mcpServerManager == null ? Map.of() : mcpServerManager.failures());
        mcp.put("servers", mcpServerManager == null ? List.of() : mcpServerManager.serverSnapshots());
        data.put("mcp", mcp);

        Map<String, Object> skills = new LinkedHashMap<>();
        skills.put("enabled", skillCatalog != null && skillCatalog.enabled());
        skills.put("count", skillCatalog == null ? 0 : skillCatalog.all().size());
        skills.put("roots", skillCatalog == null
                ? List.of()
                : skillCatalog.roots().stream().map(Object::toString).toList());
        data.put("skills", skills);
        return ApiResponse.ok(data);
    }

    /**
     * 让 MCP 服务端与技能库重新发现一次（改了 config.toml / 新增了 SKILL.md 之后不用重启）。
     *
     * <p>只重扫「外部来源」，不动 HTTP 白名单：配置文件的变更本就该走重启，重扫会让
     * 「跑了一半的 run 引用的工具突然换了」——那是另一个问题，不该混在这个动作里。
     */
    @PostMapping("/reload")
    public ApiResponse<Map<String, Object>> reload() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (mcpServerManager != null && mcpServerManager.enabled()) {
            mcpServerManager.reload();
            data.put("mcpTools", mcpServerManager.tools().size());
            data.put("mcpFailures", mcpServerManager.failures());
        }
        if (skillCatalog != null && skillCatalog.enabled()) {
            data.put("skills", skillCatalog.rescan());
        }
        // 外部来源变了，白名单必须跟着重装 —— 否则 reload 只更新了「发现结果」，
        // 执行器查的还是启动时那份快照，新工具照样「未注册」。
        toolRegistry.refreshExternal();
        data.put("toolCount", toolRegistry.ids().size());
        return ApiResponse.ok(data);
    }

    private Map<String, Object> describe(ToolRegistry.RegisteredTool tool) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", tool.id());
        item.put("kind", tool.kind().name().toLowerCase(java.util.Locale.ROOT));
        item.put("description", tool.description());
        item.put("mutating", tool.mutating());
        item.put("idempotent", tool.idempotent());
        if (tool.definition() != null) {
            item.put("method", tool.method());
            item.put("url", ToolUrlSummary.describe(tool.uri()));
        }
        if (tool.mcp() != null) {
            item.put("serverId", tool.mcp().serverId());
            item.put("mcpTool", tool.mcp().name());
            item.put("inputSchema", tool.mcp().inputSchema());
        }
        if (tool.skillAction() != null) {
            item.put("skillAction", tool.skillAction());
        }
        return item;
    }
}
