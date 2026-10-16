package com.aiflow.engine.tool.mcp;

import com.aiflow.engine.config.ToolProperties;
import com.aiflow.engine.tool.SsrfGuard;
import com.aiflow.engine.tool.ToolBlockedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Streamable HTTP 客户端对着一个假服务端跑：会话 id、SSE 解析、鉴权、404 重握手、超时。
 *
 * <p>夹具是 {@link FakeMcpHttpServerMain}（JDK 自带的 HttpServer），所以整条链路离线可跑、
 * 不依赖任何外部 MCP 服务端。
 */
class McpHttpJsonRpcClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpServerConfig config(String url) {
        return McpServerConfig.http("remote", url, Map.of(), null, 5_000, 5_000, List.of(), "test");
    }

    private static McpServerConfig config(String url, Map<String, String> headers, String authTokenEnv,
                                          long callTimeoutMs) {
        return McpServerConfig.http("remote", url, headers, authTokenEnv, 5_000, callTimeoutMs, List.of(), "test");
    }

    @Test
    void initializesListsAndCallsOverJson() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true);
             McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(config(server.url()), MAPPER, null)) {
            client.handshake();

            JsonNode tools = client.listTools().path("tools");
            assertThat(tools).hasSize(3);
            assertThat(tools.get(0).path("name").asText()).isEqualTo("echo");

            JsonNode result = client.callTool("echo", MAPPER.createObjectNode().put("msg", "hi"));
            assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("http-echo:hi");
            assertThat(client.serverInfo().path("name").asText()).isEqualTo("fake-mcp-http");
            assertThat(server.initializeCount()).isEqualTo(1);
            assertThat(server.errors()).isEmpty();
        }
    }

    @Test
    void parsesSseResponsesAndIgnoresUnrelatedEvents() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, true, true);
             McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(config(server.url()), MAPPER, null)) {
            client.handshake();

            JsonNode result = client.callTool("echo", MAPPER.createObjectNode().put("msg", "sse"));

            assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("http-echo:sse");
            assertThat(server.errors()).isEmpty();
        }
    }

    @Test
    void sendsAuthorizationHeaderFromConfiguration() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(true, "test-token-123", false, true);
             McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(
                     config(server.url(), Map.of("Authorization", "Bearer test-token-123"), null, 5_000),
                     MAPPER, null)) {
            client.handshake();

            assertThat(client.listTools().path("tools")).hasSize(3);
        }
    }

    @Test
    void resolvesAuthorizationFromEnvironmentVariable() throws IOException {
        Function<String, String> env = name -> "FAKE_MCP_TOKEN".equals(name) ? "env-token-456" : null;
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(true, "env-token-456", false, true);
             McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(
                     config(server.url(), Map.of(), "FAKE_MCP_TOKEN", 5_000), MAPPER, null, env)) {
            client.handshake();

            assertThat(client.listTools().path("tools")).hasSize(3);
        }
    }

    @Test
    void reportsAuthFailureWithActionableHint() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(true, "test-token-123", false, true);
             McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(config(server.url()), MAPPER, null)) {
            assertThatThrownBy(client::handshake)
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("HTTP 401")
                    .hasMessageContaining("鉴权失败");
        }
    }

    @Test
    void rehandshakesAndRetriesOnceWhenSessionExpires() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true);
             McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(config(server.url()), MAPPER, null)) {
            client.handshake();
            server.expireNextSession();

            JsonNode result = client.callTool("echo", MAPPER.createObjectNode().put("msg", "again"));

            assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("http-echo:again");
            assertThat(server.initializeCount()).isEqualTo(2);
        }
    }

    @Test
    void timesOutOnSlowStreamAndKeepsClientUsable() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, true, true);
             McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(
                     config(server.url(), Map.of(), null, 400), MAPPER, null)) {
            client.handshake();

            assertThatThrownBy(() -> client.callTool("slow", MAPPER.createObjectNode()))
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("超时");

            // 超时不该把客户端打成不可用：下一条消息仍然能走通（HTTP 形态没有进程要回收）。
            assertThat(client.callTool("echo", MAPPER.createObjectNode().put("msg", "ok"))
                    .path("content").get(0).path("text").asText()).isEqualTo("http-echo:ok");
        }
    }

    @Test
    void failsClearlyWhenUrlIsNotAnMcpEndpoint() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, false);
             McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(
                     config("http://127.0.0.1:" + server.port() + "/definitely-not-an-mcp-path"), MAPPER, null)) {
            assertThatThrownBy(client::handshake)
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("404");
        }
    }

    @Test
    void blocksRequestWhenHostIsNotWhitelisted() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            SsrfGuard guard = new SsrfGuard(new ToolProperties(true, 5_000, 65_536, 8, 3,
                    List.of(), List.of(), List.of(server.port()), Map.of()));
            try (McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(config(server.url()), MAPPER, guard)) {
                assertThatThrownBy(client::handshake)
                        .isInstanceOf(ToolBlockedException.class)
                        .hasMessageContaining("allow-hosts");
            }
            assertThat(server.initializeCount()).isZero();
        }
    }

    @Test
    void allowsRequestWhenPrivateHostIsWhitelisted() throws IOException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            SsrfGuard guard = new SsrfGuard(new ToolProperties(true, 5_000, 65_536, 8, 3,
                    List.of("127.0.0.1"), List.of("127.0.0.1"), List.of(server.port()), Map.of()));
            try (McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(config(server.url()), MAPPER, guard)) {
                client.handshake();

                assertThat(client.listTools().path("tools")).hasSize(3);
            }
        }
    }

    @Test
    void closeTerminatesSessionOnServer() throws IOException, InterruptedException {
        try (FakeMcpHttpServerMain server = FakeMcpHttpServerMain.start(false, null, false, true)) {
            McpHttpJsonRpcClient client = new McpHttpJsonRpcClient(config(server.url()), MAPPER, null);
            client.handshake();
            client.close();

            long deadline = System.nanoTime() + 2_000_000_000L;
            while (server.deleteCount() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(server.deleteCount()).isPositive();
        }
    }
}
