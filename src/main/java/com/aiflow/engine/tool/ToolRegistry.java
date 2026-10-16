package com.aiflow.engine.tool;

import com.aiflow.engine.config.ToolProperties;
import com.aiflow.engine.tool.mcp.McpServerManager;
import com.aiflow.engine.tool.mcp.McpToolDescriptor;
import com.aiflow.engine.tool.skill.SkillCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具白名单：只有配置里声明过、且 enabled 的工具能被调用，节点无法凭空发起任意请求。
 *
 * <p>装配期就把配置错误拒掉（url 为空、host 不在白名单、方法不支持）：这类问题拖到运行时，
 * 表现是「某个工作流在某次运行里失败」，而配置错误的成本应该在启动那一刻付。
 *
 * <p>三个来源登记在同一个白名单里，但**各自的开关独立** —— 它们的风险不是一类：
 * HTTP 工具出网（要过 SSRF 四道校验）、MCP 服务端拉起本地进程（命令必须由配置显式声明）、
 * 技能库只读本地文件。统一的是「能不能被调用」这件事，不统一的是「要多严」。
 *
 * <p>MCP 工具在启动时发现一次并快照进注册表：工具清单来自服务端的 {@code tools/list}，
 * 属于外部事实，热更新它要连带处理「正在跑的 run 引用了已消失的工具」，当前选择是重启生效。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

    /** 技能库工具 id（固定两个，见 SkillCatalog 的说明）。 */
    public static final String SKILL_LIST = "skill.list";
    public static final String SKILL_READ = "skill.read";

    private final ToolProperties properties;
    private final SsrfGuard ssrfGuard;
    private final McpServerManager mcpServerManager;
    private final SkillCatalog skillCatalog;
    /**
     * 当前白名单快照。读多写少（只在启动与 {@code reload} 时整体替换），所以用 volatile 持有**不可变映射**：
     * run 线程读到的永远是一份完整快照，不会在遍历途中被另一条线程改掉（就地增删 LinkedHashMap 是会坏掉的）。
     */
    private volatile Map<String, RegisteredTool> tools = Map.of();

    /** 测试与最小装配用：不接 MCP 与技能库。 */
    public ToolRegistry(ToolProperties properties, SsrfGuard ssrfGuard) {
        this(properties, ssrfGuard, null, null);
    }

    @Autowired
    public ToolRegistry(ToolProperties properties, SsrfGuard ssrfGuard, McpServerManager mcpServerManager,
                        SkillCatalog skillCatalog) {
        this.properties = properties;
        this.ssrfGuard = ssrfGuard;
        this.mcpServerManager = mcpServerManager;
        this.skillCatalog = skillCatalog;
        this.tools = assemble(null);
        if (properties.enabled()) {
            log.info("工具白名单已装配: {}（allow-hosts={}）", tools.keySet(), properties.allowHosts());
        }
    }

    /**
     * 装配一份新快照。
     *
     * @param existingHttp 已有的 HTTP 工具（{@code reload} 时沿用启动期校验过的结果）；null = 从配置重新读一遍
     */
    private Map<String, RegisteredTool> assemble(Map<String, RegisteredTool> existingHttp) {
        Map<String, RegisteredTool> fresh = new LinkedHashMap<>();
        if (existingHttp == null) {
            registerHttpTools(fresh);
        } else {
            fresh.putAll(existingHttp);
        }
        registerMcpTools(fresh);
        registerSkillTools(fresh);
        return Collections.unmodifiableMap(fresh);
    }

    /**
     * 重新装配「外部来源」（MCP 服务端与技能库）并整体替换快照 —— {@code POST /api/v1/tools/reload} 走这里。
     *
     * <p>为什么是「整体替换」而不是就地增删：run 线程随时可能在读（{@code require} / {@code ids}），
     * 一边遍历一边改的 Map 会给出半截视图；替换成一个不可变快照则天然原子。
     *
     * <p>HTTP 白名单沿用启动时的结果：配置文件的变更本就该走重启，混进重扫会让「跑了一半的 run 引用的工具
     * 突然换了定义」，那是另一个问题，不该混在这个动作里（与接口注释同一口径）。
     *
     * <p>调用顺序是「先让各来源重新发现（{@code McpServerManager#reload} / {@code SkillCatalog#rescan}），
     * 再调这里重装快照」—— 本方法只负责把**当前**发现结果固化成新快照，不自己触发发现；
     * 组合顺序见 {@code ToolController#reload}。
     */
    public synchronized void refreshExternal() {
        Map<String, RegisteredTool> http = new LinkedHashMap<>();
        for (Map.Entry<String, RegisteredTool> entry : tools.entrySet()) {
            if (entry.getValue().kind() == ToolKind.HTTP) {
                http.put(entry.getKey(), entry.getValue());
            }
        }
        this.tools = assemble(http);
        if (properties.enabled()) {
            log.info("工具白名单已重装（外部来源）: {}", tools.keySet());
        }
    }

    private void registerHttpTools(Map<String, RegisteredTool> target) {
        for (Map.Entry<String, ToolProperties.Tool> entry : properties.registry().entrySet()) {
            String id = entry.getKey();
            ToolProperties.Tool definition = entry.getValue();
            if (!definition.enabled()) {
                log.info("tool {} 已禁用，跳过注册", id);
                continue;
            }
            if (!properties.enabled()) {
                log.info("tool {} 已定义，但 aiflow.tools.enabled=false，跳过注册", id);
                continue;
            }
            target.put(id, validate(id, definition));
        }
    }

    private void registerMcpTools(Map<String, RegisteredTool> target) {
        if (isBlank(mcpServerManager) || !properties.enabled() || !mcpServerManager.enabled()) {
            return;
        }
        for (McpToolDescriptor descriptor : mcpServerManager.tools()) {
            String id = mcpServerManager.toolId(descriptor);
            RegisteredTool tool = new RegisteredTool(id, ToolKind.MCP, descriptor.description(),
                    null, null, null, true, descriptor, null);
            RegisteredTool previous = target.putIfAbsent(id, tool);
            if (previous != null) {
                log.warn("MCP 工具 id 与已有工具冲突，保留先注册的: {}", id);
            }
        }
    }

    private void registerSkillTools(Map<String, RegisteredTool> target) {
        if (isBlank(skillCatalog) || !properties.enabled() || !skillCatalog.enabled()) {
            return;
        }
        target.put(SKILL_LIST, new RegisteredTool(SKILL_LIST, ToolKind.SKILL,
                "列出本机技能库里的技能（参数 query 关键词、limit 条数）",
                null, null, null, true, null, SKILL_LIST));
        target.put(SKILL_READ, new RegisteredTool(SKILL_READ, ToolKind.SKILL,
                "读取某个技能的正文（参数 name 技能名、maxChars 字符上限）",
                null, null, null, true, null, SKILL_READ));
    }

    private static boolean isBlank(Object bean) {
        return bean == null;
    }

    private RegisteredTool validate(String id, ToolProperties.Tool definition) {
        String method = definition.method() == null ? "GET" : definition.method().strip().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalStateException("工具 " + id + " 的方法不支持: " + method + "（可用 " + ALLOWED_METHODS + "）");
        }
        URI uri;
        try {
            uri = ssrfGuard.validate(definition.url());
        } catch (ToolBlockedException e) {
            throw new IllegalStateException("工具 " + id + " 配置非法: " + e.getMessage(), e);
        }
        boolean idempotent = !"none".equalsIgnoreCase(definition.idempotency());
        return new RegisteredTool(id, ToolKind.HTTP, definition.description(), definition, uri, method, idempotent,
                null, null);
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public Optional<RegisteredTool> find(String id) {
        return Optional.ofNullable(tools.get(id));
    }

    public RegisteredTool require(String id) {
        if (!properties.enabled()) {
            throw new ToolBlockedException("工具调用未启用（aiflow.tools.enabled=false）");
        }
        return find(id).orElseThrow(() -> new ToolBlockedException(
                "工具未注册: " + id + "（当前白名单 " + tools.keySet() + "，先在 aiflow.tools.registry 里声明）"));
    }

    public Set<String> ids() {
        return Set.copyOf(tools.keySet());
    }

    /** 按来源分类，供 {@code GET /api/v1/tools} 与排障使用。 */
    public List<RegisteredTool> list() {
        return List.copyOf(tools.values());
    }

    /**
     * 注册表里的一条工具。
     *
     * @param kind       来源（决定由哪个执行器分支处理）
     * @param description 给编排者/模型看的一句话说明
     * @param definition  HTTP 工具的原始定义（非 HTTP 为 null）
     * @param uri         HTTP 工具的最终 URI（非 HTTP 为 null）
     * @param method      HTTP 方法（非 HTTP 为 null）
     * @param idempotent  是否按入参指纹去重
     * @param mcp         MCP 工具描述符（非 MCP 为 null）
     * @param skillAction 技能库动作（{@link #SKILL_LIST} / {@link #SKILL_READ}，非技能为 null）
     */
    public record RegisteredTool(String id, ToolKind kind, String description, ToolProperties.Tool definition,
                                 URI uri, String method, boolean idempotent, McpToolDescriptor mcp,
                                 String skillAction) {

        /** HTTP 里 GET/HEAD 天然幂等；MCP 工具语义未知，一律按「可能写」对待。 */
        public boolean mutating() {
            if (kind == ToolKind.MCP) {
                return true;
            }
            return kind == ToolKind.HTTP && !"GET".equals(method) && !"HEAD".equals(method);
        }
    }
}
