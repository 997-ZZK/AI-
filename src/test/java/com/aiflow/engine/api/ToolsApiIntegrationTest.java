package com.aiflow.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工具清单接口的装配测试：三来源合并后的白名单、失败原因、技能库概览。
 *
 * <p>三个属性都是**显式指定**的：技能根目录指向测试自己造的目录（不去扫开发机上的真实技能），
 * MCP 导入路径指向一个不存在的文件（否则本机装了 Codex 时会真的拉起它的子进程，测试变成环境依赖）。
 * 顺带覆盖一条重要行为：**一个 MCP 服务端起不来不影响清单接口可用**，失败原因要能从响应里读到。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ToolsApiIntegrationTest {

    /** 技能根目录放在 target 下：不删用户文件，mvn clean 顺手带走。 */
    private static final Path SKILL_ROOT = prepareSkillRoot();

    private static final Path ABSENT_IMPORT = Path.of("target", "test-artifacts", "absent-config.toml")
            .toAbsolutePath();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static Path prepareSkillRoot() {
        Path root = Path.of("target", "test-artifacts", "tools-api-skills").toAbsolutePath();
        try {
            Path file = root.resolve("api-skill").resolve("SKILL.md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    ---
                    name: api-skill
                    description: 接口测试用的技能
                    ---

                    正文
                    """);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return root;
    }

    @DynamicPropertySource
    static void tools(DynamicPropertyRegistry registry) {
        registry.add("aiflow.tools.enabled", () -> "true");
        registry.add("aiflow.tools.skills.enabled", () -> "true");
        registry.add("aiflow.tools.skills.roots[0]", SKILL_ROOT::toString);
        registry.add("aiflow.tools.mcp.enabled", () -> "true");
        registry.add("aiflow.tools.mcp.servers[0].id", () -> "api-broken");
        registry.add("aiflow.tools.mcp.servers[0].command", () -> "definitely-not-a-real-binary-api-test");
        registry.add("aiflow.tools.mcp.imports[0].path", ABSENT_IMPORT::toString);
        registry.add("aiflow.tools.mcp.imports[1].path", ABSENT_IMPORT::toString);
    }

    @Test
    void listsThreeSourcesAndReportsMcpFailure() throws Exception {
        JsonNode data = call(get("/api/v1/tools"));

        assertThat(data.path("enabled").asBoolean()).isTrue();
        List<String> ids = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        data.path("tools").forEach(tool -> {
            ids.add(tool.path("id").asText());
            kinds.add(tool.path("kind").asText());
        });
        assertThat(ids).contains("skill.list", "skill.read");
        assertThat(kinds).contains("skill");
        // 不起的服务端不进白名单，但必须留下可读的原因
        assertThat(ids).noneMatch(id -> id.startsWith("mcp.api-broken"));
        assertThat(data.path("mcp").path("enabled").asBoolean()).isTrue();
        assertThat(data.path("mcp").path("failures").has("api-broken")).isTrue();
        assertThat(data.path("mcp").path("failures").path("api-broken").asText()).contains("启动失败");
        // 技能库：根目录按配置顺序回显，测试目录排在第一位
        assertThat(data.path("skills").path("enabled").asBoolean()).isTrue();
        assertThat(data.path("skills").path("roots").get(0).asText()).isEqualTo(SKILL_ROOT.toString());
        assertThat(data.path("skills").path("count").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void reloadRediscoversExternalSourcesWithoutRestart() throws Exception {
        JsonNode data = call(post("/api/v1/tools/reload"));

        assertThat(data.path("skills").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(data.path("mcpFailures").has("api-broken")).isTrue();
        assertThat(data.path("toolCount").asInt()).isEqualTo(2);
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asInt()).isZero();
        return body.path("data");
    }
}
