package com.aiflow.engine.tool.mcp;

import com.aiflow.engine.config.ToolProperties;
import com.aiflow.engine.tool.SsrfGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 热更新一致性：快照指纹、代数、drain、以及「老式 SSE 走同一套装配」。
 *
 * <p>这些用例对着真的假服务端跑（{@link FakeMcpHttpServerMain} / {@link FakeMcpSseServerMain}），
 * 因为要验证的正是「服务端侧工具清单变了之后，引擎怎么反应」—— 用 mock 掉客户端的方式是验证不了的。
 */
class McpServerManagerHotReloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpServerManager manager(SsrfGuard guard, McpToolProperties.Server... servers) {
        McpToolProperties properties = new McpToolProperties(true, "mcp.", 5_000, 5_000, 5_000,
                List.of(servers), List.of());
        McpServerManager manager = new McpServerManager(properties, new McpConfigImporter(MAPPER), MAPPER, guard);
        manager.start();
        return manager;
    }

    private static SsrfGuard guard(int port) {
        return new SsrfGuard(new ToolProperties(true, 5_000, 65_536, 8, 3, List.of("127.0.0.1"),
                List.of("127.0.0.1"), List.of(port), Map.of()));
    }

    private static McpToolProperties.Server httpServer(String id, String url) {
        return new McpToolProperties.Server(id, true, null, List.of(), Map.of(), List.of(), null,
                url, Map.of(), null, 5_000L, 5_000L, List.of(), "http", null);
    }

    private static McpToolProperties.Server sseServer(String id, String url) {
        return new McpToolProperties.Server(id, true, null, List.of(), Map.of(), List.of(), null,
                url, Map.of(), null, 5_000L, 5_000L, List.of(), "sse", null);
    }

    private static McpToolDescriptor tool(McpServerManager manager, String name) {
        return manager.tools().stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void registersToolsFromLegacySseServer() throws IOException {
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start()) {
            McpServerManager manager = manager(guard(server.port()), sseServer("legacy", server.url()));

            assertThat(manager.failures()).isEmpty();
            assertThat(manager.tools()).extracting(McpToolDescriptor::name).containsExactly("echo", "boom", "slow");
            assertThat(manager.serverSnapshots().get(0))
                    .containsEntry("transport", "sse")
                    .containsEntry("url", server.url());

            McpToolResult result = manager.call("legacy", "echo", Map.of("msg", "hi"));

            assertThat(result.text()).isEqualTo("sse-echo:hi");
            assertThat(server.toolCallCount()).isEqualTo(1);
            manager.close();
        }
    }

    @Test
    void legacySseWithoutSsrfGuardIsRejected() throws IOException {
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start()) {
            McpServerManager manager = manager(null, sseServer("legacy", server.url()));

            assertThat(manager.failures().get("legacy")).contains("SsrfGuard");
            assertThat(manager.tools()).isEmpty();
            manager.close();
        }
    }

    @Test
    void snapshotCarriesVersionGenerationAndAuthMode() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            McpServerManager manager = manager(guard(server.port()), httpServer("remote", server.url()));

            Map<String, Object> snapshot = manager.serverSnapshots().get(0);

            assertThat(snapshot.get("version")).asString().isNotBlank();
            assertThat(snapshot.get("generation")).isEqualTo(1L);
            assertThat(snapshot.get("auth")).isEqualTo("none");
            assertThat(snapshot.get("loadedAt")).asString().isNotBlank();
            assertThat(snapshot.get("inFlight")).isEqualTo(0);
            manager.close();
        }
    }

    @Test
    void reloadBumpsGenerationAndVersionWhenToolSetChanges() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            McpServerManager manager = manager(guard(server.port()), httpServer("remote", server.url()));
            String before = manager.version("remote");

            server.addTool("extra", "新增的工具");
            manager.reload();

            assertThat(manager.generation()).isEqualTo(2);
            assertThat(manager.version("remote")).isNotEqualTo(before);
            assertThat(manager.tools()).extracting(McpToolDescriptor::name)
                    .containsExactly("echo", "boom", "slow", "extra");
            assertThat(manager.call("remote", "extra", Map.of("msg", "x")).text()).isEqualTo("http-extra:x");
            manager.close();
        }
    }

    @Test
    void verifyToolFailsWhenDefinitionChangedByReload() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            McpServerManager manager = manager(guard(server.port()), httpServer("remote", server.url()));
            McpToolDescriptor pinned = tool(manager, "echo");

            server.describeTool("echo", "换了实现的同名工具");
            manager.reload();

            assertThatThrownBy(() -> manager.verifyTool(pinned))
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("定义在本次运行期间被热更新改变")
                    .hasMessageContaining(pinned.fingerprint());
            // 新快照自己是对的：重跑一次就能用新定义继续跑。
            assertThatCode(() -> manager.verifyTool(tool(manager, "echo"))).doesNotThrowAnyException();
            manager.close();
        }
    }

    @Test
    void verifyToolFailsWhenToolDisappearedAndPassesForUnchangedSiblings() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            McpServerManager manager = manager(guard(server.port()), httpServer("remote", server.url()));
            McpToolDescriptor pinnedEcho = tool(manager, "echo");
            McpToolDescriptor pinnedBoom = tool(manager, "boom");

            server.hideTool("echo");
            server.addTool("extra", "新增的工具");
            manager.reload();

            assertThatThrownBy(() -> manager.verifyTool(pinnedEcho))
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("已不存在");
            // 关键取舍：只影响变了的那个工具，同一个服务端上没变的工具照常可用 ——
            // 否则一次「服务端新增了一个工具」的热更新会把所有在跑的 run 全打断。
            assertThatCode(() -> manager.verifyTool(pinnedBoom)).doesNotThrowAnyException();
            manager.close();
        }
    }

    @Test
    void reloadWaitsForInFlightCallInsteadOfKillingIt() throws Exception {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            server.setSlowMillis(800);
            McpServerManager manager = manager(guard(server.port()), httpServer("remote", server.url()));
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<McpToolResult> pending = pool.submit(() -> manager.call("remote", "slow", Map.of()));
                Thread.sleep(150);

                long startedAt = System.nanoTime();
                manager.reload();
                long reloadMs = (System.nanoTime() - startedAt) / 1_000_000;

                // 旧行为是「直接关掉旧客户端」，那个在途调用会被腰斩；现在先 drain 再关。
                assertThat(pending.get(5, TimeUnit.SECONDS).text()).isEqualTo("slow done");
                assertThat(reloadMs).isGreaterThanOrEqualTo(400L);
                assertThat(manager.failures()).isEmpty();
            } finally {
                pool.shutdownNow();
                manager.close();
            }
        }
    }

    @Test
    void reloadClosesOldClientWhenDrainTimesOut() throws Exception {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            server.setSlowMillis(3_000);
            McpToolProperties properties = new McpToolProperties(true, "mcp.", 5_000, 8_000, 200,
                    List.of(httpServer("remote", server.url())), List.of());
            McpServerManager manager = new McpServerManager(properties, new McpConfigImporter(MAPPER), MAPPER,
                    guard(server.port()));
            manager.start();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<McpToolResult> pending = pool.submit(() -> manager.call("remote", "slow", Map.of()));
                Thread.sleep(150);

                long startedAt = System.nanoTime();
                manager.reload();
                long reloadMs = (System.nanoTime() - startedAt) / 1_000_000;

                // drain 超时必须放行关闭流程：一次热更新不该被一个卡死的调用无限期拖住。
                assertThat(reloadMs).isLessThan(2_000L);
                assertThat(manager.failures()).isEmpty();
                pending.cancel(true);
            } finally {
                pool.shutdownNow();
                manager.close();
            }
        }
    }
}
