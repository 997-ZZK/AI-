package com.aiflow.engine.tool.mcp;

import com.aiflow.engine.config.ToolProperties;
import com.aiflow.engine.tool.SsrfGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 客户端与服务端装配的集成测试：真的拉起一个子进程（stdio），或真的起一个 HTTP 服务端，
 * 走完整的 initialize -> tools/list -> tools/call。
 *
 * <p>夹具是测试源码里的 {@link FakeMcpServerMain} 与 {@link FakeMcpHttpServerMain}，
 * 所以两条链路都离线可跑、不依赖任何外部包。
 */
class McpServerManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpServerManager manager(McpToolProperties.Server... servers) {
        return manager(null, servers);
    }

    private McpServerManager manager(SsrfGuard ssrfGuard, McpToolProperties.Server... servers) {
        McpToolProperties properties = new McpToolProperties(true, "mcp.", 5_000, 5_000,
                List.of(servers), List.of());
        McpServerManager manager = new McpServerManager(properties, new McpConfigImporter(MAPPER), MAPPER,
                ssrfGuard);
        manager.start();
        return manager;
    }

    private static SsrfGuard ssrfGuard(int port, boolean allowHost) {
        return new SsrfGuard(new ToolProperties(true, 5_000, 65_536, 8, 3,
                allowHost ? List.of("127.0.0.1") : List.of(), List.of("127.0.0.1"), List.of(port), Map.of()));
    }

    private static McpToolProperties.Server httpServer(String id, String url) {
        return new McpToolProperties.Server(id, true, null, List.of(), Map.of(), List.of(), null,
                url, Map.of(), null, 5_000L, 5_000L, List.of());
    }

    private static McpToolProperties.Server fakeServer(List<String> allowedTools, long callTimeoutMs) {
        return new McpToolProperties.Server("fake", true, javaExecutable(),
                List.of("-cp", System.getProperty("java.class.path"), FakeMcpServerMain.class.getName()),
                Map.of(), List.of(), null, null, Map.of(), null, 15_000L, callTimeoutMs, allowedTools);
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    @Test
    void discoversToolsOverStdio() {
        McpServerManager manager = manager(fakeServer(List.of(), 5_000));

        assertThat(manager.failures()).isEmpty();
        assertThat(manager.tools()).extracting(McpToolDescriptor::name)
                .containsExactly("echo", "boom", "slow");
        assertThat(manager.toolId(manager.tools().get(0))).isEqualTo("mcp.fake.echo");
        assertThat(manager.tools().get(0).description()).isEqualTo("原样回显入参 msg");
        assertThat(manager.serverSnapshots().get(0)).containsEntry("transport", "stdio");
        manager.close();
    }

    @Test
    void callsToolAndNormalizesTextContent() {
        McpServerManager manager = manager(fakeServer(List.of(), 5_000));

        McpToolResult result = manager.call("fake", "echo", Map.of("msg", "hi"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).isEqualTo("echo:hi");
        manager.close();
    }

    @Test
    void surfacesServerSideErrorFlag() {
        McpServerManager manager = manager(fakeServer(List.of(), 5_000));

        McpToolResult result = manager.call("fake", "boom", Map.of("msg", "x"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).isEqualTo("boom:x");
        manager.close();
    }

    @Test
    void honoursToolWhitelist() {
        McpServerManager manager = manager(fakeServer(List.of("echo"), 5_000));

        assertThat(manager.tools()).extracting(McpToolDescriptor::name).containsExactly("echo");
        manager.close();
    }

    @Test
    void callTimeoutKillsProcessAndReportsStderrTail() {
        McpServerManager manager = manager(fakeServer(List.of(), 400));

        assertThatThrownBy(() -> manager.call("fake", "slow", Map.of()))
                .isInstanceOf(McpCallException.class)
                .hasMessageContaining("超时")
                .hasMessageContaining("stderr 末");
        manager.close();
    }

    @Test
    void unusableServerIsRecordedButDoesNotBreakOthers() {
        McpToolProperties.Server broken = new McpToolProperties.Server("broken", true,
                "definitely-not-a-real-binary-42", List.of(), Map.of(), List.of(), null, null, Map.of(), null,
                2_000L, 2_000L, List.of());
        McpServerManager manager = manager(broken, fakeServer(List.of(), 5_000));

        assertThat(manager.failures()).containsKey("broken");
        assertThat(manager.failures().get("broken")).contains("启动失败");
        assertThat(manager.tools()).isNotEmpty();
        manager.close();
    }

    @Test
    void disabledManagerRegistersNothing() {
        McpToolProperties properties = new McpToolProperties(false, "mcp.", 1_000, 1_000,
                List.of(fakeServer(List.of(), 1_000)), List.of());
        McpServerManager manager = new McpServerManager(properties, new McpConfigImporter(MAPPER), MAPPER);

        manager.start();

        assertThat(manager.tools()).isEmpty();
        assertThat(manager.failures()).isEmpty();
    }

    @Test
    void readsImportedCodexConfigFromDisk(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.toml");
        Files.writeString(config, """
                [mcp_servers.imported]
                command = 'C:\\tools\\node.exe'
                args = ["--serve"]
                env_vars = ["CODEX_HOME"]
                startup_timeout_sec = 30

                [mcp_servers.imported.env]
                NODE_PATH = 'C:\\tools\\node_modules'
                """);
        McpToolProperties properties = new McpToolProperties(true, "mcp.", 5_000, 5_000, List.of(),
                List.of(new McpToolProperties.ImportSpec(config.toString(), "auto", "codex-")));
        McpServerManager manager = new McpServerManager(properties, new McpConfigImporter(MAPPER), MAPPER);

        manager.start();

        // 导入进来的服务端指向一个不存在的可执行文件，装配必然失败；
        // 失败信息里带着配置里的命令路径，正好证明「文件被读到、字段被解析对」。
        assertThat(manager.failures()).containsOnlyKeys("codex-imported");
        assertThat(manager.failures().get("codex-imported")).contains("C:\\tools\\node.exe");
        assertThat(manager.tools()).isEmpty();
        manager.close();
    }

    @Test
    void discoversAndCallsToolsOverStreamableHttp() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            McpServerManager manager = manager(ssrfGuard(server.port(), true), httpServer("remote", server.url()));

            assertThat(manager.failures()).isEmpty();
            assertThat(manager.tools()).extracting(McpToolDescriptor::name)
                    .containsExactly("echo", "boom", "slow");
            assertThat(manager.serverSnapshots().get(0))
                    .containsEntry("transport", "http")
                    .containsEntry("url", server.url());

            McpToolResult result = manager.call("remote", "echo", Map.of("msg", "hi"));

            assertThat(result.error()).isFalse();
            assertThat(result.text()).isEqualTo("http-echo:hi");
            assertThat(server.toolCallCount()).isEqualTo(1);
            manager.close();
        }
    }

    @Test
    void httpServerWithoutSsrfGuardIsRejected() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            McpServerManager manager = manager(httpServer("remote", server.url()));

            assertThat(manager.failures().get("remote")).contains("SsrfGuard");
            assertThat(manager.tools()).isEmpty();
            assertThat(server.initializeCount()).isZero();
            manager.close();
        }
    }

    @Test
    void httpServerOutsideWhitelistIsRejectedBeforeConnecting() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            McpServerManager manager = manager(ssrfGuard(server.port(), false), httpServer("remote", server.url()));

            assertThat(manager.failures().get("remote")).contains("allow-hosts");
            assertThat(server.initializeCount()).isZero();
            manager.close();
        }
    }

    @Test
    void serverWithBothCommandAndUrlIsRejected() {
        McpToolProperties.Server confused = new McpToolProperties.Server("confused", true, javaExecutable(),
                List.of(), Map.of(), List.of(), null, "https://example.com/mcp", Map.of(), null,
                1_000L, 1_000L, List.of());
        McpServerManager manager = manager(confused);

        assertThat(manager.failures().get("confused")).contains("command 与 url");
        assertThat(manager.tools()).isEmpty();
        manager.close();
    }

    @Test
    void serverWithoutCommandOrUrlIsRejected() {
        McpToolProperties.Server empty = new McpToolProperties.Server("empty", true, null,
                List.of(), Map.of(), List.of(), null, null, Map.of(), null, 1_000L, 1_000L, List.of());
        McpServerManager manager = manager(empty);

        assertThat(manager.failures().get("empty")).contains("缺少 command 或 url");
        manager.close();
    }

    @Test
    void unauthorizedHttpServerIsRecordedInFailures() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(true, "test-token-123", false, true)) {
            McpServerManager manager = manager(ssrfGuard(server.port(), true), httpServer("remote", server.url()));

            assertThat(manager.failures().get("remote")).contains("401").contains("鉴权失败");
            assertThat(manager.tools()).isEmpty();
            manager.close();
        }
    }

    @Test
    void httpServerWithMissingTokenEnvFailsFast() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            McpToolProperties.Server remote = new McpToolProperties.Server("remote", true, null,
                    List.of(), Map.of(), List.of(), null, server.url(), Map.of(),
                    "AIFLOW_DEFINITELY_MISSING_TOKEN_VAR", 5_000L, 5_000L, List.of());
            McpServerManager manager = manager(ssrfGuard(server.port(), true), remote);

            // 变量没设就必须在装配时失败：静默不带凭证地发出去，只会变成一串 401 或一次匿名调用。
            assertThat(manager.failures().get("remote")).contains("AIFLOW_DEFINITELY_MISSING_TOKEN_VAR")
                    .contains("未设置");
            assertThat(manager.tools()).isEmpty();
            assertThat(server.initializeCount()).isZero();
            manager.close();
        }
    }
}
