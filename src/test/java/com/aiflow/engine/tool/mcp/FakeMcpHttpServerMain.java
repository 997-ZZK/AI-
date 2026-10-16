package com.aiflow.engine.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试与联调用的最小 MCP 服务端（Streamable HTTP 单端点 POST）。
 *
 * <p>跟 {@link FakeMcpServerMain}（stdio 版）一样，存在的理由是「不引真实 MCP 服务端、不联网、不需要 npx」：
 * 这里只实现协议本身，顺带把客户端最容易写错的几件事都摆在明面上 ——
 * {@code Mcp-Session-Id} 的下发与回带校验、{@code Accept} 必须包含 {@code text/event-stream}、
 * SSE 响应里混入心跳注释与无关事件、会话过期时的 404、鉴权 401。
 *
 * <p>响应形态（JSON / SSE）、是否强制会话、是否校验 token 都是构造参数 —— 一个夹具覆盖多种服务端行为，
 * 比给每种行为各写一个夹具更容易看出「客户端到底依赖了什么」。
 *
 * <p>作为 main 直接跑起来就是联调用的假服务端：{@code java -cp target/test-classes;target/classes
 * com.aiflow.engine.tool.mcp.FakeMcpHttpServerMain 18790 /mcp}（第二个参数是端口，第三个是路径）。
 */
public final class FakeMcpHttpServerMain implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final String path;
    private final boolean requireToken;
    private final String token;
    private final boolean sse;
    private final boolean enforceSession;
    private final String sessionId = "fake-session-" + UUID.randomUUID();
    private final AtomicInteger initializeCount = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private final AtomicBoolean expireNextSession = new AtomicBoolean();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    /** 工具清单是「外部事实」，会在服务端侧变化：热更新用例靠这两个可变钩子制造变化。 */
    private final java.util.Set<String> hiddenTools = Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    private final Map<String, String> toolDescriptions = Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private final Map<String, String> extraTools = Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private volatile long slowMillis = 5000;

    public FakeMcpHttpServerMain(int port, String path, boolean requireToken, String token, boolean sse,
                                 boolean enforceSession) throws IOException {
        this.path = path;
        this.requireToken = requireToken;
        this.token = token;
        this.sse = sse;
        this.enforceSession = enforceSession;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext(path, this::handle);
        this.server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-mcp-http");
            thread.setDaemon(true);
            return thread;
        }));
        this.server.start();
    }

    public static FakeMcpHttpServerMain start(boolean requireToken, String token, boolean sse, boolean enforceSession)
            throws IOException {
        return new FakeMcpHttpServerMain(0, "/mcp", requireToken, token, sse, enforceSession);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port() + path;
    }

    public int initializeCount() {
        return initializeCount.get();
    }

    public int toolCallCount() {
        return toolCalls.get();
    }

    public int deleteCount() {
        return deletes.get();
    }

    public List<String> errors() {
        return List.copyOf(errors);
    }

    /** 让下一个带会话的请求收到 404：模拟服务端把会话弄丢了。 */
    public void expireNextSession() {
        expireNextSession.set(true);
    }
    /** 让 tools/list 不再包含某个工具：模拟服务端下线工具。 */
    public void hideTool(String name) {
        hiddenTools.add(name);
    }

    /** 改掉某个工具的描述：模拟同名工具换了定义（指纹必须随之改变）。 */
    public void describeTool(String name, String description) {
        toolDescriptions.put(name, description);
    }

    /** 新增一个工具：模拟服务端上线新工具。 */
    public void addTool(String name, String description) {
        extraTools.put(name, description);
    }

    /** 调整 slow 工具的睡眠时长（热更新 drain 用例需要它短一点）。 */
    public void setSlowMillis(long millis) {
        this.slowMillis = millis;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String httpMethod = exchange.getRequestMethod();
            if ("DELETE".equals(httpMethod)) {
                deletes.incrementAndGet();
                send(exchange, 202, new byte[0], "text/plain");
                return;
            }
            if (!"POST".equals(httpMethod)) {
                send(exchange, 405, "expect POST".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.contains("application/json")) {
                send(exchange, 415, "expect application/json".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept == null || !accept.contains("text/event-stream")) {
                send(exchange, 406, "Accept must include text/event-stream".getBytes(StandardCharsets.UTF_8),
                        "text/plain");
                return;
            }
            if (requireToken) {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization == null || !authorization.equals("Bearer " + token)) {
                    send(exchange, 401, "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8),
                            "application/json");
                    return;
                }
            }
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode message = MAPPER.readTree(raw);
            String rpcMethod = message.path("method").asText("");
            boolean notification = !message.has("id") || message.get("id").isNull();
            if (enforceSession && !"initialize".equals(rpcMethod)) {
                String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                if (expireNextSession.compareAndSet(true, false)) {
                    send(exchange, 404, "session expired".getBytes(StandardCharsets.UTF_8), "text/plain");
                    return;
                }
                if (session == null || !session.equals(sessionId)) {
                    send(exchange, 404, "unknown session".getBytes(StandardCharsets.UTF_8), "text/plain");
                    return;
                }
            }
            if (notification) {
                send(exchange, 202, new byte[0], "text/plain");
                return;
            }
            byte[] payload = MAPPER.writeValueAsBytes(respondFor(rpcMethod, message));
            if (enforceSession && "initialize".equals(rpcMethod)) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", sessionId);
            }
            if (sse) {
                String stream = ": heartbeat\n\n"
                        + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\","
                        + "\"params\":{\"note\":\"与本次请求无关的事件\"}}\n\n"
                        + "event: message\ndata: " + new String(payload, StandardCharsets.UTF_8) + "\n\n";
                send(exchange, 200, stream.getBytes(StandardCharsets.UTF_8), "text/event-stream");
                return;
            }
            send(exchange, 200, payload, "application/json");
        } catch (Exception e) {
            errors.add(e.toString());
            send(exchange, 500, e.toString().getBytes(StandardCharsets.UTF_8), "text/plain");
        } finally {
            exchange.close();
        }
    }

    private JsonNode respondFor(String rpcMethod, JsonNode message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", message.get("id"));
        switch (rpcMethod) {
            case "initialize" -> {
                initializeCount.incrementAndGet();
                ObjectNode result = response.putObject("result");
                result.put("protocolVersion", "2024-11-05");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "fake-mcp-http").put("version", "0.1.0");
            }
            case "tools/list" -> response.set("result", toolsList());
            case "tools/call" -> response.set("result", callTool(message.path("params")));
            default -> {
                ObjectNode error = response.putObject("error");
                error.put("code", -32601);
                error.put("message", "Method not found: " + rpcMethod);
            }
        }
        return response;
    }

    private ObjectNode toolsList() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        addTool(tools, "echo", "原样回显入参 msg");
        addTool(tools, "boom", "总是返回 isError=true");
        addTool(tools, "slow", "睡 5 秒后返回（用于超时测试）");
        extraTools.forEach((name, description) -> addTool(tools, name, description));
        return result;
    }

    private void addTool(ArrayNode tools, String name, String fallbackDescription) {
        if (hiddenTools.contains(name)) {
            return;
        }
        String description = toolDescriptions.getOrDefault(name, fallbackDescription);
        tools.addObject().put("name", name).put("description", description)
                .putObject("inputSchema").put("type", "object");
    }

    private ObjectNode callTool(JsonNode params) {
        toolCalls.incrementAndGet();
        String name = params.path("name").asText("");
        String argument = params.path("arguments").path("msg").asText("");
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        switch (name) {
            case "echo" -> content.addObject().put("type", "text").put("text", "http-echo:" + argument);
            case "boom" -> {
                result.put("isError", true);
                content.addObject().put("type", "text").put("text", "http-boom:" + argument);
            }
            case "slow" -> {
                try {
                    Thread.sleep(slowMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                content.addObject().put("type", "text").put("text", "slow done");
            }
            default -> {
                if (extraTools.containsKey(name)) {
                    content.addObject().put("type", "text").put("text", "http-extra:" + argument);
                } else {
                    result.put("isError", true);
                    content.addObject().put("type", "text").put("text", "unknown tool: " + name);
                }
            }
        }
        return result;
    }

    private void send(HttpExchange exchange, int status, byte[] payload, String contentType) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        if (payload.length == 0) {
            return;
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18790;
        String path = args.length > 1 ? args[1] : "/mcp";
        String token = System.getenv("FAKE_MCP_TOKEN");
        FakeMcpHttpServerMain server = new FakeMcpHttpServerMain(port, path, token != null, token, true, true);
        System.out.println("fake-mcp-http listening on " + server.url()
                + (token == null ? "（无鉴权；设置 FAKE_MCP_TOKEN 可启用 Bearer 校验）" : "（要求 Bearer token）"));
        Thread.currentThread().join();
    }
}
