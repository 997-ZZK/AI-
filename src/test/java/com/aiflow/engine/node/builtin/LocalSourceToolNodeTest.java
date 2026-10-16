package com.aiflow.engine.node.builtin;

import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.config.ToolProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.tool.HttpToolInvoker;
import com.aiflow.engine.tool.SsrfGuard;
import com.aiflow.engine.config.ToolAuditProperties;
import com.aiflow.engine.config.ToolPolicyProperties;
import com.aiflow.engine.tool.ToolAuthorizer;
import com.aiflow.engine.tool.ToolInvocationGuard;
import com.aiflow.engine.tool.audit.ToolAuditService;
import com.aiflow.engine.tool.ToolRegistry;
import com.aiflow.engine.tool.skill.SkillCatalog;
import com.aiflow.engine.tool.skill.SkillToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具节点的「本地来源」分支：技能库工具真的能被工作流调用，护栏与 HTTP 分支同源。
 *
 * <p>MCP 分支的协议互操作在 {@code McpServerManagerTest} 里对着真实子进程验证，
 * 这一层只验证「节点 -> 注册表 -> 来源分发」这条接线。
 */
class LocalSourceToolNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RUN_ID = UUID.randomUUID().toString();

    private static SkillCatalog catalog(Path root) throws IOException {
        Path file = root.resolve("handbook").resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                ---
                name: handbook
                description: 员工手册要点
                ---
                # 员工手册
                忘打卡要在三个工作日内补卡。
                """);
        SkillToolProperties properties = new SkillToolProperties(true, List.of(root.toString()), 20_000, 50, 4);
        SkillCatalog catalog = new SkillCatalog(properties, MAPPER);
        catalog.start();
        return catalog;
    }

    private static NodeContext context(Map<String, Object> params) {
        NodeDef node = new NodeDef("tool", "tool.call", "工具", params, Set.of(), null, null);
        return new NodeContext(UUID.fromString(RUN_ID), UUID.randomUUID(), UUID.randomUUID(), node,
                new HashMap<>(), new InMemoryRunEventBus(EngineProperties.defaults()), () -> false);
    }

    private static ToolCallNodeExecutor executor(ToolProperties properties, SkillCatalog catalog) {
        SsrfGuard guard = new SsrfGuard(properties);
        ToolRegistry registry = new ToolRegistry(properties, guard, null, catalog);
        return new ToolCallNodeExecutor(registry, guard, new ToolInvocationGuard(properties),
                new HttpToolInvoker(properties), MAPPER, null, catalog,
                new ToolAuthorizer(new ToolPolicyProperties(true, false, List.of())),
                new ToolAuditService(new ToolAuditProperties(true, 100)));
    }

    private static ToolProperties enabledSkills() {
        return new ToolProperties(true, 3_000, 65_536, 8, 3, List.of(), List.of(), List.of(80, 443), Map.of());
    }

    @Test
    void skillReadReturnsBodyIntoVariable(@TempDir Path root) throws IOException {
        SkillCatalog catalog = catalog(root);
        ToolCallNodeExecutor executor = executor(enabledSkills(), catalog);

        NodeResult result = executor.execute(context(Map.of("tool", "skill.read",
                "args", Map.of("name", "handbook"), "resultVariable", "skillText")));

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(result.output()).containsEntry("kind", "skill");
        assertThat((String) result.output().get("body")).contains("三个工作日内补卡");
    }

    @Test
    void skillListUsesQueryArgument(@TempDir Path root) throws IOException {
        SkillCatalog catalog = catalog(root);
        ToolCallNodeExecutor executor = executor(enabledSkills(), catalog);

        NodeResult hit = executor.execute(context(Map.of("tool", "skill.list",
                "args", Map.of("query", "员工"))));
        NodeResult miss = executor.execute(context(Map.of("tool", "skill.list",
                "args", Map.of("query", "不存在的东西"))));

        assertThat((String) hit.output().get("body")).contains("handbook");
        assertThat((String) miss.output().get("body")).isEqualTo("[]");
    }

    @Test
    void unknownSkillIsANodeFailure(@TempDir Path root) throws IOException {
        SkillCatalog catalog = catalog(root);
        ToolCallNodeExecutor executor = executor(enabledSkills(), catalog);

        NodeResult result = executor.execute(context(Map.of("tool", "skill.read",
                "args", Map.of("name", "nope"))));

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error()).contains("技能不存在");
    }

    @Test
    void skillsDisabledMeansToolNotRegistered(@TempDir Path root) throws IOException {
        SkillCatalog catalog = catalog(root);
        SkillToolProperties disabled = new SkillToolProperties(false, List.of(root.toString()), 20_000, 50, 4);
        SkillCatalog off = new SkillCatalog(disabled, MAPPER);

        NodeResult result = executor(enabledSkills(), off).execute(context(Map.of("tool", "skill.read",
                "args", Map.of("name", "handbook"))));

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error()).contains("未注册").contains("skill.read");
        assertThat(catalog.all()).isNotEmpty();
    }

    @Test
    void masterSwitchOffBlocksSkillToolsToo(@TempDir Path root) throws IOException {
        SkillCatalog catalog = catalog(root);
        ToolProperties off = new ToolProperties(false, 3_000, 65_536, 8, 3, List.of(), List.of(),
                List.of(80, 443), Map.of());

        NodeResult result = executor(off, catalog).execute(context(Map.of("tool", "skill.read",
                "args", Map.of("name", "handbook"))));

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error()).contains("未启用");
    }
}
