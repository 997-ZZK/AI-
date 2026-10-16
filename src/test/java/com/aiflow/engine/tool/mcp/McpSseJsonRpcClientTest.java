package com.aiflow.engine.tool.mcp;

import com.aiflow.engine.config.ToolProperties;
import com.aiflow.engine.tool.SsrfGuard;
import com.aiflow.engine.tool.ToolBlockedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 老式 SSE 双端点客户端对着一个假服务端跑：端点发现、请求/响应分离、断线重连、鉴权、超时、SSRF。
 *
 * <p>夹具是 {@link FakeMcpSseServerMain}（JDK 自带的 HttpServer），所以整条链路离线可跑。
 * 这些用例存在的意义：老式 SSE 的状态机与单端点完全不同，靠「读一遍代码觉得对」是验证不了断线重连的。
 */
class McpSseJsonRpcClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpServerConfig config(String url) {
        return McpServerConfig.sse("legacy", url, Map.of(), null, 5_000, 5_000, List.of(), "test");
    }

    private static SsrfGuard guard(int port, boolean allowHost) {
        return new SsrfGuard(new ToolProperties(true, 5_000, 65_536, 8, 3,
                allowHost ? List.of("127.0.0.1") : List.of(), List.of("127.0.0.1"), List.of(port), Map.of()));
    }

    @Test
    void initializesListsAndCallsOverSseDualEndpoint() throws IOException {
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start();
             McpSseJsonRpcClient client = new McpSseJsonRpcClient(config(server.url()), MAPPER, null)) {
            client.handshake();

            JsonNode tools = client.listTools().path("tools");
            assertThat(tools).hasSize(3);
            JsonNode result = client.callTool("echo", MAPPER.createObjectNode().put("msg", "hi"));

            // 响应是从长连接上回来的，而且夹具里混了通知与别的请求的响应：能拿到正确结果就说明配对逻辑对。
            assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("sse-echo:hi");
            assertThat(client.serverInfo().path("name").asText()).isEqualTo("fake-mcp-sse");
            assertThat(server.sessionsOpened()).isEqualTo(1);
            assertThat(server.initializeCount()).isEqualTo(1);
            assertThat(server.errors()).isEmpty();
        }
    }

    @Test
    void resolvesRelativeEndpointFromServerEvent() throws IOException {
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start();
             McpSseJsonRpcClient client = new McpSseJsonRpcClient(config(server.url()), MAPPER, null)) {
            client.handshake();

            // endpoint 事件给的是相对路径 /messages?sessionId=...，客户端必须按建流地址解析后才 POST。
            assertThat(client.listTools().path("tools")).hasSize(3);
            assertThat(server.postCount()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void sendsBearerTokenFromEnvironmentToBothEndpoints() throws IOException {
        Function<String, String> env = name -> "FAKE_MCP_TOKEN".equals(name) ? "sse-token-777" : null;
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start(
                FakeMcpSseServerMain.Options.withToken("sse-token-777"));
             McpSseJsonRpcClient client = new McpSseJsonRpcClient(
                     McpServerConfig.sse("legacy", server.url(), Map.of(), "FAKE_MCP_TOKEN", 5_000, 5_000,
                             List.of(), "test"),
                     MAPPER, null, env)) {
            // 静态令牌在构造时兑现（缺环境变量属于配置错误，装配期就该失败）：
            // 这里同时验证 GET 长连接与 POST 投递都带上了同一个 Bearer。
            client.handshake();

            assertThat(client.listTools().path("tools")).hasSize(3);
            assertThat(server.rejectedAuthCount()).isZero();
        }
    }

    @Test
    void reportsAuthFailureWithActionableHint() throws IOException {
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start(
                FakeMcpSseServerMain.Options.withToken("sse-token-777"));
             McpSseJsonRpcClient client = new McpSseJsonRpcClient(config(server.url()), MAPPER, null)) {
            assertThatThrownBy(client::handshake)
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("401")
                    .hasMessageContaining("鉴权失败");
        }
    }

    @Test
    void failsClearlyWhenGetIsNotAnEventStream() throws IOException {
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start(
                FakeMcpSseServerMain.Options.normal().withJsonInsteadOfSse());
             McpSseJsonRpcClient client = new McpSseJsonRpcClient(config(server.url()), MAPPER, null)) {
            assertThatThrownBy(client::handshake)
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("text/event-stream");
        }
    }

    @Test
    void failsClearlyWhenEndpointEventNeverArrives() throws IOException {
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start(
                FakeMcpSseServerMain.Options.normal().withoutEndpointEvent());
             McpSseJsonRpcClient client = new McpSseJsonRpcClient(
                     McpServerConfig.sse("legacy", server.url(), Map.of(), null, 800, 5_000, List.of(), "test"),
                     MAPPER, null)) {
            assertThatThrownBy(client::handshake)
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("endpoint");
        }
    }

    @Test
    void reconnectsAndRetriesOnceWhenMessageEndpointExpires() throws IOException {
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start();
             McpSseJsonRpcClient client = new McpSseJsonRpcClient(config(server.url()), MAPPER, null)) {
            client.handshake();
            server.expireNextPost();

            JsonNode result = client.callTool("echo", MAPPER.createObjectNode().put("msg", "again"));

            assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("sse-echo:again");
            assertThat(server.sessionsOpened()).isEqualTo(2);
            assertThat(server.initializeCount()).isEqualTo(2);
        }
    }

    @Test
    void reconnectsWhenServerDropsTheStream() throws IOException {
        // 推 2 条响应后服务端关流：握手 + tools/list 正好把连接用完，下一条消息必须自己重连。
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start(
                FakeMcpSseServerMain.Options.normal().droppingAfter(2));
             McpSseJsonRpcClient client = new McpSseJsonRpcClient(config(server.url()), MAPPER, null)) {
            client.handshake();
            client.listTools();

            JsonNode result = client.callTool("echo", MAPPER.createObjectNode().put("msg", "after-drop"));

            assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("sse-echo:after-drop");
            assertThat(server.sessionsOpened()).isEqualTo(2);
        }
    }

    @Test
    void timesOutOnSlowCallAndKeepsClientUsable() throws IOException {
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start();
             McpSseJsonRpcClient client = new McpSseJsonRpcClient(
                     McpServerConfig.sse("legacy", server.url(), Map.of(), null, 5_000, 400, List.of(), "test"),
                     MAPPER, null)) {
            client.handshake();

            assertThatThrownBy(() -> client.callTool("slow", MAPPER.createObjectNode()))
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("超时");

            assertThat(client.callTool("echo", MAPPER.createObjectNode().put("msg", "ok"))
                    .path("content").get(0).path("text").asText()).isEqualTo("sse-echo:ok");
        }
    }

    @Test
    void blocksConnectionWhenHostIsNotWhitelisted() throws IOException {
        try (FakeMcpSseServerMain server = FakeMcpSseServerMain.start()) {
            try (McpSseJsonRpcClient client = new McpSseJsonRpcClient(config(server.url()), MAPPER,
                    guard(server.port(), false))) {
                assertThatThrownBy(client::handshake)
                        .isInstanceOf(ToolBlockedException.class)
                        .hasMessageContaining("allow-hosts");
            }
            assertThat(server.sessionsOpened()).isZero();
        }
    }

    @Test
    void blocksEndpointThatPointsOutsideTheWhitelist() throws IOException {
        // 服务端给的投递地址指向链路本地地址：只校验第一次连接是不够的，端点也必须过白名单。
        // 端点端口与建流地址同端口 —— 这样被拦下的原因只能是 host，而不是先撞上端口规则（否则用例测偏了）。
        int roguePort = freePort();
        String rogue = "http://169.254.169.254:" + roguePort + "/messages";
        try (FakeMcpSseServerMain rogueServer = new FakeMcpSseServerMain(roguePort, "/sse",
                FakeMcpSseServerMain.Options.normal().withEndpoint(rogue));
             McpSseJsonRpcClient client = new McpSseJsonRpcClient(config(rogueServer.url()), MAPPER,
                     guard(rogueServer.port(), true))) {
            assertThatThrownBy(client::handshake)
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("169.254.169.254");
            assertThat(rogueServer.postCount()).isZero();
        }
    }

    /** 端点要在起服务端之前写死同端口，所以先探一个空闲端口（理论上仍有竞态，测试够用）。 */
    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
