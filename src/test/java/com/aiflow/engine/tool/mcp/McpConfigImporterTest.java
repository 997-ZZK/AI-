package com.aiflow.engine.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 从本机已有 Agent 配置里导入 MCP 服务端：格式兼容性与「不认识的形态要怎么处理」。 */
class McpConfigImporterTest {

    private final McpConfigImporter importer = new McpConfigImporter(new ObjectMapper());

    @Test
    void parsesCodexConfigToml() {
        String toml = """
                # Codex 的 config.toml 片段
                model = "gpt-5"

                [mcp_servers.node_repl]
                args = []
                command = 'C:\\Users\\me\\AppData\\Local\\OpenAI\\Codex\\runtimes\\cua_node\\hash\\bin\\node_repl.exe'
                env_vars = ["CODEX_WINDOWS_REGISTERED_CORE"]
                startup_timeout_sec = 120

                [mcp_servers.node_repl.env]
                CODEX_HOME = 'C:\\Users\\me\\.codex'
                NODE_REPL_NODE_MODULE_DIRS = 'C:\\Users\\me\\node_modules'

                [mcp_servers.http_only]
                url = "https://example.com/mcp"
                bearer_token_env_var = "EXAMPLE_TOKEN"
                http_headers = { "X-Tenant" = "acme", "X-Trace" = "${TRACE_ID:-off}" }
                """;

        List<McpServerConfig> servers = importer.parseCodexToml(toml, "codex:config.toml", "codex-", 20_000, 30_000);

        assertThat(servers).hasSize(2);

        // url 形态现在会被真正导入，而不是像以前那样被跳过：两条导入路径共用同一个解析器。
        McpServerConfig http = servers.get(1);
        assertThat(http.id()).isEqualTo("codex-http_only");
        assertThat(http.transport()).isEqualTo(McpTransport.HTTP);
        assertThat(http.url()).hasToString("https://example.com/mcp");
        assertThat(http.authTokenEnv()).isEqualTo("EXAMPLE_TOKEN");
        assertThat(http.headers()).containsEntry("X-Tenant", "acme");
        assertThat(http.headers()).containsEntry("X-Trace", "${TRACE_ID:-off}");

        McpServerConfig server = servers.get(0);
        assertThat(server.id()).isEqualTo("codex-node_repl");
        assertThat(server.command()).isEqualTo(
                "C:\\Users\\me\\AppData\\Local\\OpenAI\\Codex\\runtimes\\cua_node\\hash\\bin\\node_repl.exe");
        assertThat(server.args()).isEmpty();
        assertThat(server.passThroughEnv()).containsExactly("CODEX_WINDOWS_REGISTERED_CORE");
        assertThat(server.startupTimeoutMs()).isEqualTo(120_000);
        assertThat(server.env()).containsEntry("CODEX_HOME", "C:\\Users\\me\\.codex");
        assertThat(server.env()).containsEntry("NODE_REPL_NODE_MODULE_DIRS", "C:\\Users\\me\\node_modules");
    }

    @Test
    void parsesClaudeJsonIncludingLegacySseEntries() {
        String json = """
                {
                  "mcpServers": {
                    "local-fs": {
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
                      "env": { "TOKEN": "abc" }
                    },
                    "remote": { "type": "sse", "url": "https://example.com/sse" }
                  }
                }
                """;

        List<McpServerConfig> servers = importer.parseClaudeJson(json, "claude:.mcp.json", "claude-", 20_000, 30_000);

        // type=sse 不再跳过：它是老式双端点形态，引擎有专门的客户端与状态机（见 McpSseJsonRpcClient）。
        assertThat(servers).hasSize(2);
        McpServerConfig local = servers.stream().filter(item -> item.id().equals("claude-local-fs")).findFirst()
                .orElseThrow();
        assertThat(local.command()).isEqualTo("npx");
        assertThat(local.args()).containsExactly("-y", "@modelcontextprotocol/server-filesystem", "/tmp");
        assertThat(local.env()).containsEntry("TOKEN", "abc");
        McpServerConfig legacy = servers.stream().filter(item -> item.id().equals("claude-remote")).findFirst()
                .orElseThrow();
        assertThat(legacy.transport()).isEqualTo(McpTransport.SSE);
        assertThat(legacy.url()).hasToString("https://example.com/sse");
    }

    @Test
    void parsesClaudeHttpServersAndRejectsUnsupportedUrls() {
        String json = """
                {
                  "mcpServers": {
                    "remote-http": {
                      "type": "http",
                      "url": "https://mcp.example.com/mcp",
                      "headers": { "Authorization": "Bearer ${EXAMPLE_TOKEN}" }
                    },
                    "legacy-sse": { "type": "sse", "url": "https://example.com/sse" },
                    "bad-url": { "type": "http", "url": "ftp://example.com/mcp" }
                  }
                }
                """;

        List<McpServerConfig> servers = importer.parseClaudeJson(json, "claude:.mcp.json", "claude-", 20_000, 30_000);

        // remote-http 与 legacy-sse 都进来（形态不同、各有客户端），只有 bad-url 被跳过 ——
        // 一条错配置不该影响同文件里其他服务端。
        assertThat(servers).hasSize(2);
        McpServerConfig remote = servers.stream().filter(item -> item.id().equals("claude-remote-http"))
                .findFirst().orElseThrow();
        assertThat(remote.transport()).isEqualTo(McpTransport.HTTP);
        assertThat(remote.headers()).containsEntry("Authorization", "Bearer ${EXAMPLE_TOKEN}");
        McpServerConfig legacy = servers.stream().filter(item -> item.id().equals("claude-legacy-sse"))
                .findFirst().orElseThrow();
        assertThat(legacy.transport()).isEqualTo(McpTransport.SSE);
    }

    @Test
    void missingFileIsSkippedWithoutException(@TempDir Path dir) {
        McpToolProperties properties = new McpToolProperties(true, "mcp.", 5_000, 5_000, List.of(),
                List.of(new McpToolProperties.ImportSpec(dir.resolve("nope.toml").toString(), "auto", "")));

        assertThat(importer.load(properties)).isEmpty();
    }

    @Test
    void expandsHomePrefix() {
        assertThat(McpConfigImporter.expandHome("~/.codex/config.toml"))
                .isEqualTo(System.getProperty("user.home") + "/.codex/config.toml");
        assertThat(McpConfigImporter.expandHome("C:\\cfg\\x.json")).isEqualTo("C:\\cfg\\x.json");
    }

    @Test
    void loadsConfiguredFilesFromDisk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".mcp.json");
        Files.writeString(file, "{\"mcpServers\": {\"x\": {\"command\": \"node\", \"args\": [\"a\"]}}}");
        McpToolProperties properties = new McpToolProperties(true, "mcp.", 5_000, 5_000, List.of(),
                List.of(new McpToolProperties.ImportSpec(file.toString(), "auto", "")));

        List<McpServerConfig> servers = importer.load(properties);

        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).id()).isEqualTo("x");
        assertThat(servers.get(0).source()).isEqualTo(file.toString());
    }
}
