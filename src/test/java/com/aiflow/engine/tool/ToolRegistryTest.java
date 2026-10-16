package com.aiflow.engine.tool;

import com.aiflow.engine.config.ToolProperties;
import com.aiflow.engine.tool.mcp.FakeMcpHttpServerMain;
import com.aiflow.engine.tool.mcp.McpConfigImporter;
import com.aiflow.engine.tool.mcp.McpServerManager;
import com.aiflow.engine.tool.mcp.McpToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    @Test
    void disabledRegistryRejectsEveryCall() {
        ToolRegistry registry = registry(ToolProperties.disabled(), Map.of("t", tool("GET", "https://api.example.com/x")));

        assertThat(registry.enabled()).isFalse();
        assertThat(registry.ids()).isEmpty();
        assertThatThrownBy(() -> registry.require("t"))
                .isInstanceOf(ToolBlockedException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    void unknownToolIsRejectedWithCurrentWhitelist() {
        ToolRegistry registry = registry(enabledProperties(List.of("api.example.com"), Map.of()),
                Map.of("search", tool("GET", "https://api.example.com/search")));

        assertThat(registry.require("search").id()).isEqualTo("search");
        assertThatThrownBy(() -> registry.require("delete-everything"))
                .hasMessageContaining("未注册")
                .hasMessageContaining("search");
    }

    @Test
    void startupRejectsToolWhoseHostIsNotInAllowlist() {
        assertThatThrownBy(() -> registry(enabledProperties(List.of("api.example.com"), Map.of()),
                Map.of("evil", tool("GET", "https://exfiltrate.example.org/collect"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evil")
                .hasMessageContaining("allow-hosts");
    }

    @Test
    void startupRejectsUnsupportedMethodAndUserInfo() {
        assertThatThrownBy(() -> registry(enabledProperties(List.of("api.example.com"), Map.of()),
                Map.of("weird", tool("TRACE", "https://api.example.com/x"))))
                .hasMessageContaining("方法不支持");

        assertThatThrownBy(() -> registry(enabledProperties(List.of("api.example.com"), Map.of()),
                Map.of("leaky", tool("GET", "https://user:pw@api.example.com/x"))))
                .hasMessageContaining("userinfo");
    }

    @Test
    void disabledToolIsNotRegistered() {
        ToolProperties.Tool disabled = new ToolProperties.Tool("关掉的工具", "GET", "https://api.example.com/x",
                Map.of(), false, "content");
        ToolRegistry registry = registry(enabledProperties(List.of("api.example.com"), Map.of()), Map.of("off", disabled));

        assertThat(registry.find("off")).isEmpty();
    }

    @Test
    void reloadRefreshesExternalToolsInsteadOfKeepingStartupSnapshot() throws IOException {
        // reload 的价值就在「不重启也能让新工具进来」：只更新发现结果、不重装白名单的话，
        // 执行器查到的还是启动时那份快照，新工具照样报「未注册」。
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, false)) {
            McpServerManager manager = mcpManager(server);
            ToolProperties properties = enabledProperties(List.of("127.0.0.1"), Map.of(), List.of(server.port()));
            ToolRegistry registry = new ToolRegistry(properties, new SsrfGuard(properties, host -> List.of(loopback())),
                    manager, null);
            try {
                assertThat(registry.ids()).contains("mcp.fake.echo", "mcp.fake.boom", "mcp.fake.slow");

                server.hideTool("echo");
                server.addTool("extra", "新加的工具");
                // 与 ToolController#reload 同一顺序：先让各来源重新发现，再整体重装快照
                manager.reload();
                registry.refreshExternal();

                assertThat(registry.ids()).contains("mcp.fake.extra", "mcp.fake.boom", "mcp.fake.slow")
                        .doesNotContain("mcp.fake.echo");
                assertThat(registry.require("mcp.fake.extra").kind()).isEqualTo(ToolKind.MCP);
                assertThatThrownBy(() -> registry.require("mcp.fake.echo"))
                        .isInstanceOf(ToolBlockedException.class)
                        .hasMessageContaining("未注册");
            } finally {
                manager.close();
            }
        }
    }

    private ToolRegistry registry(ToolProperties properties, Map<String, ToolProperties.Tool> tools) {
        ToolProperties merged = new ToolProperties(properties.enabled(), properties.timeoutMs(),
                properties.maxBodyBytes(), properties.maxCallsPerRun(), properties.loopThreshold(),
                properties.allowHosts(), properties.allowPrivateHosts(), properties.allowPorts(), tools);
        SsrfGuard guard = new SsrfGuard(merged, host -> List.of(publicAddress()));
        return new ToolRegistry(merged, guard);
    }

    private McpServerManager mcpManager(FakeMcpHttpServerMain server) {
        McpToolProperties mcpProperties = new McpToolProperties(true, "mcp.", 5_000, 5_000, 5_000,
                List.of(new McpToolProperties.Server("fake", true, null, List.of(), Map.of(), List.of(), null,
                        server.url(), Map.of(), null, 5_000L, 5_000L, List.of())),
                List.of());
        McpServerManager manager = new McpServerManager(mcpProperties, new McpConfigImporter(new ObjectMapper()),
                new ObjectMapper(), new SsrfGuard(enabledProperties(List.of("127.0.0.1"), Map.of(), List.of(server.port())),
                        host -> List.of(loopback())));
        manager.start();
        return manager;
    }

    private ToolProperties enabledProperties(List<String> allowHosts, Map<String, ToolProperties.Tool> ignored) {
        return new ToolProperties(true, 3_000, 65_536, 8, 3, allowHosts, List.of(), List.of(80, 443), Map.of());
    }

    /** 需要把假服务端的端口放进白名单时用这个重载（回环地址也要显式放行）。 */
    private ToolProperties enabledProperties(List<String> allowHosts, Map<String, ToolProperties.Tool> ignored,
                                             List<Integer> allowPorts) {
        return new ToolProperties(true, 3_000, 65_536, 8, 3, allowHosts, List.of("127.0.0.1"), allowPorts, Map.of());
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolProperties.Tool tool(String method, String url) {
        return new ToolProperties.Tool("desc", method, url, Map.of(), true, "content");
    }

    private static InetAddress publicAddress() {
        try {
            return InetAddress.getByName("93.184.216.34");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}