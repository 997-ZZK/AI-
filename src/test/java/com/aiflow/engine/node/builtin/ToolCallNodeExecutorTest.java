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
import com.aiflow.engine.tool.ToolInvocationGuard;
import com.aiflow.engine.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具节点：白名单 -> 幂等 -> 循环/次数护栏 -> 真调用（打桩用 JDK HttpServer）。
 *
 * <p>每个用例都从「服务端被调用了几次」这个可观测事实出发断言 —— 护栏的价值就在这个计数上，
 * 只看返回码会把「幂等没生效、真发了两次请求」当成通过。
 */
class ToolCallNodeExecutorTest {

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> LAST_QUERY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static volatile int responseStatus = 200;
    private static volatile String responseBody = "{\"ok\":true}";
    private static HttpServer server;
    private static String toolUrl;

    @BeforeAll
    static void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/tool", exchange -> {
                CALLS.incrementAndGet();
                LAST_QUERY.set(exchange.getRequestURI().getQuery());
                LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            server.start();
            toolUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/tool";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        CALLS.set(0);
        LAST_QUERY.set(null);
        LAST_BODY.set(null);
        responseStatus = 200;
        responseBody = "{\"ok\":true}";
    }

    @Test
    void callsToolAndExposesBodyToVariables() {
        Map<String, Object> variables = new HashMap<>(Map.of("input", "hello world"));
        NodeContext context = context(Map.of("tool", "echo-get", "args", Map.of("q", "${input}")), variables);

        NodeResult result = executor(properties(Map.of("echo-get", getTool()), 8, 3)).execute(context);

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(result.output()).containsEntry("httpStatus", 200)
                .containsEntry("toolId", "echo-get")
                .containsEntry("callIndex", 1);
        assertThat(variables.get("toolResult")).isEqualTo("{\"ok\":true}");
        assertThat(variables.get("toolStatus")).isEqualTo(200);
        assertThat(LAST_QUERY.get()).isEqualTo("q=hello+world");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void successOutputDoesNotExposeQueryCredentials() {
        String marker = "private-tool-query-marker";
        ToolProperties.Tool tool = getTool(toolUrl + "?token=" + marker);
        NodeContext context = context(Map.of("tool", "echo-get"), new HashMap<>());

        NodeResult result = executor(properties(Map.of("echo-get", tool), 8, 3)).execute(context);

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(result.output().get("url")).isEqualTo(toolUrl);
        assertThat(String.valueOf(result.output())).doesNotContain(marker, "token=");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void httpErrorBecomesNodeFailureWithSnippet() {
        responseStatus = 500;
        responseBody = "upstream boom";
        NodeContext context = context(Map.of("tool", "echo-get"), new HashMap<>());

        NodeResult result = executor(properties(Map.of("echo-get", getTool()), 8, 3)).execute(context);

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error()).contains("HTTP 500").contains("upstream boom");
    }

    @Test
    void redirectIsRefusedInsteadOfFollowed() {
        responseStatus = 302;
        NodeContext context = context(Map.of("tool", "echo-get"), new HashMap<>());

        NodeResult result = executor(properties(Map.of("echo-get", getTool()), 8, 3)).execute(context);

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error())
                .as("跟随 302 等于把白名单校验过的 URL 换成对方指定的 URL")
                .contains("拒绝跟随重定向");
    }

    @Test
    void replaysIdempotentWriteInsteadOfRepeatingSideEffect() {
        ToolCallNodeExecutor executor = executor(properties(Map.of("write", postTool("content")), 8, 3));
        Map<String, Object> params = Map.of("tool", "write", "args", Map.of("name", "订单-1"));

        NodeResult first = executor.execute(context(params, new HashMap<>()));
        NodeResult second = executor.execute(context(params, new HashMap<>()));

        assertThat(first.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(second.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(second.output()).containsEntry("replayed", true);
        assertThat(CALLS.get())
                .as("同一次运行里同指纹的写操作只能真的发一次：重试不能再下一单")
                .isEqualTo(1);
        assertThat(LAST_BODY.get()).contains("订单-1");
    }

    @Test
    void nonIdempotentToolCallsEveryTime() {
        ToolCallNodeExecutor executor = executor(properties(Map.of("write", postTool("none")), 8, 3));
        Map<String, Object> params = Map.of("tool", "write", "args", Map.of("name", "订单-1"));

        executor.execute(context(params, new HashMap<>()));
        executor.execute(context(params, new HashMap<>()));

        assertThat(CALLS.get()).isEqualTo(2);
    }

    @Test
    void stopsRepeatedIdenticalCallsByLoopGuard() {
        ToolCallNodeExecutor executor = executor(properties(Map.of("echo-get", getTool()), 8, 3));
        Map<String, Object> params = Map.of("tool", "echo-get", "args", Map.of("q", "same"));

        assertThat(executor.execute(context(params, new HashMap<>())).status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(executor.execute(context(params, new HashMap<>())).status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(executor.execute(context(params, new HashMap<>())).status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        NodeResult fourth = executor.execute(context(params, new HashMap<>()));

        assertThat(fourth.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(fourth.error()).contains("循环检测").contains("阈值 3");
        assertThat(CALLS.get()).as("被判定为循环的调用不能再发出去").isEqualTo(3);
    }

    @Test
    void capsToolCallsPerRun() {
        ToolCallNodeExecutor executor = executor(properties(Map.of("echo-get", getTool()), 2, 8));

        assertThat(executor.execute(context(Map.of("tool", "echo-get", "args", Map.of("q", "1")), new HashMap<>()))
                .status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(executor.execute(context(Map.of("tool", "echo-get", "args", Map.of("q", "2")), new HashMap<>()))
                .status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        NodeResult third = executor.execute(context(Map.of("tool", "echo-get", "args", Map.of("q", "3")), new HashMap<>()));

        assertThat(third.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(third.error()).contains("次数已达上限");
    }

    @Test
    void rejectsUnregisteredToolAndDisabledRegistry() {
        NodeContext context = context(Map.of("tool", "other"), new HashMap<>());

        NodeResult unregistered = executor(properties(Map.of("echo-get", getTool()), 8, 3)).execute(context);
        assertThat(unregistered.error()).contains("未注册");

        ToolProperties disabled = new ToolProperties(false, 3_000, 65_536, 8, 3, List.of("127.0.0.1"),
                List.of("127.0.0.1"), List.of(server.getAddress().getPort()), Map.of("echo-get", getTool()));
        NodeResult off = executor(disabled).execute(context);
        assertThat(off.error()).contains("未启用");
        assertThat(CALLS.get()).isZero();
    }

    /** 同一个 runId 贯穿多次调用：护栏与幂等缓存都是「运行级」的。 */
    private static final String RUN_ID = UUID.randomUUID().toString();

    private NodeContext context(Map<String, Object> params, Map<String, Object> variables) {
        NodeDef node = new NodeDef("tool", "tool.call", "工具", params, Set.of(), null, null);
        return new NodeContext(UUID.fromString(RUN_ID), UUID.randomUUID(), UUID.randomUUID(), node, variables,
                new InMemoryRunEventBus(EngineProperties.defaults()), () -> false);
    }

    private ToolCallNodeExecutor executor(ToolProperties properties) {
        SsrfGuard guard = new SsrfGuard(properties);
        ToolRegistry registry = new ToolRegistry(properties, guard);
        return new ToolCallNodeExecutor(registry, guard, new ToolInvocationGuard(properties), new HttpToolInvoker(properties),
                new ObjectMapper());
    }

    private ToolProperties properties(Map<String, ToolProperties.Tool> tools, int maxCalls, int loopThreshold) {
        return new ToolProperties(true, 3_000, 65_536, maxCalls, loopThreshold, List.of("127.0.0.1"),
                List.of("127.0.0.1"), List.of(server.getAddress().getPort()), tools);
    }

    private static ToolProperties.Tool getTool() {
        return getTool(toolUrl);
    }

    private static ToolProperties.Tool getTool(String url) {
        return new ToolProperties.Tool("回显 GET", "GET", url, Map.of(), true, "content");
    }

    private static ToolProperties.Tool postTool(String idempotency) {
        return new ToolProperties.Tool("写入", "POST", toolUrl, Map.of(), true, idempotency);
    }
}
