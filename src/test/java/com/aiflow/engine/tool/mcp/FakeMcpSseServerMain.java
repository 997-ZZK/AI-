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
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试与联调用的最小「老式 SSE 双端点」MCP 服务端（规范 2024-11-05 的 HTTP+SSE 形态）。
 *
 * <p>它模拟的是三件真实服务端都会做的事，也是客户端最容易写错的地方：
 * <ul>
 *   <li><b>GET 长连接先下发 endpoint</b>：投递地址不在配置里，而是服务端在 {@code event: endpoint} 里给的，
 *       客户端必须按它去 POST（相对路径要能解析）</li>
 *   <li><b>响应走长连接回来</b>：POST 只回 202 空体，真正的 JSON-RPC 响应从 SSE 流上推给客户端，
 *       中间还会混入心跳注释、通知与「别的请求的响应」</li>
 *   <li><b>连接会被掐断</b>：网关空闲回收、服务端滚动重启都会发生；客户端要能重连、重新发现端点、重新握手</li>
 * </ul>
 *
 * <p>作为 main 直接跑起来就是联调用的假服务端：
 * {@code java -cp target/test-classes;target/classes com.aiflow.engine.tool.mcp.FakeMcpSseServerMain 18791 /sse}。
 */
public final class FakeMcpSseServerMain implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_MILLIS = 200;
    /** 噪声事件的内部标记：夹具用它区分「真实响应」与「混进来的无关事件」。 */
    private static final String NOISE_MARKER = "\u0001";

    /** 夹具行为：一个构造参数覆盖多种服务端行为，比给每种行为各写一个夹具更容易看出「客户端依赖了什么」。 */
    public record Options(boolean requireToken, String token, boolean sendEndpoint, boolean jsonInsteadOfSse,
                          String endpointOverride, int dropAfterMessages) {

        public static Options normal() {
            return new Options(false, null, true, false, null, 0);
        }

        public static Options withToken(String token) {
            return new Options(true, token, true, false, null, 0);
        }

        public Options withoutEndpointEvent() {
            return new Options(requireToken, token, false, jsonInsteadOfSse, endpointOverride, dropAfterMessages);
        }

        public Options withJsonInsteadOfSse() {
            return new Options(requireToken, token, sendEndpoint, true, endpointOverride, dropAfterMessages);
        }

        public Options withEndpoint(String value) {
            return new Options(requireToken, token, sendEndpoint, jsonInsteadOfSse, value, dropAfterMessages);
        }

        public Options droppingAfter(int messages) {
            return new Options(requireToken, token, sendEndpoint, jsonInsteadOfSse, endpointOverride, messages);
        }
    }

    private final HttpServer server;
    private final Options options;
    private final String ssePath;
    private final String sessionId = "fake-sse-" + UUID.randomUUID();
    private final List<BlockingQueue<String>> channels = Collections.synchronizedList(new ArrayList<>());
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger sessionsOpened = new AtomicInteger();
    private final AtomicInteger initializeCount = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicInteger rejectedTokens = new AtomicInteger();
    private final AtomicBoolean expireNextPost = new AtomicBoolean();
    private final AtomicBoolean closeStreams = new AtomicBoolean();

    public FakeMcpSseServerMain(int port, String ssePath, Options options) throws IOException {
        this.options = options;
        this.ssePath = ssePath;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext(ssePath, this::handleSse);
        this.server.createContext("/messages", this::handleMessage);
        this.server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-mcp-sse");
            thread.setDaemon(true);
            return thread;
        }));
        this.server.start();
    }

    public static FakeMcpSseServerMain start() throws IOException {
        return new FakeMcpSseServerMain(0, "/sse", Options.normal());
    }

    public static FakeMcpSseServerMain start(Options options) throws IOException {
        return new FakeMcpSseServerMain(0, "/sse", options);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port() + ssePath;
    }

    public int sessionsOpened() {
        return sessionsOpened.get();
    }

    public int initializeCount() {
        return initializeCount.get();
    }

    public int toolCallCount() {
        return toolCalls.get();
    }

    public int postCount() {
        return posts.get();
    }

    public List<String> errors() {
        return List.copyOf(errors);
    }

    /** 让下一个 POST 收到 404：模拟「会话在服务端已经没了」。 */
    public void expireNextPost() {
        expireNextPost.set(true);
    }

    /** 主动掐断所有 SSE 连接：模拟网关回收长连接。 */
    public void dropStreams() {
        closeStreams.set(true);
    }

    private void handleSse(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "expect GET".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            if (!authorized(exchange)) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8),
                        "application/json");
                return;
            }
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept == null || !accept.contains("text/event-stream")) {
                send(exchange, 406, "Accept must include text/event-stream".getBytes(StandardCharsets.UTF_8),
                        "text/plain");
                return;
            }
            sessionsOpened.incrementAndGet();
            closeStreams.set(false);
            if (options.jsonInsteadOfSse()) {
                send(exchange, 200, "{\"jsonrpc\":\"2.0\"}".getBytes(StandardCharsets.UTF_8), "application/json");
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            BlockingQueue<String> channel = new LinkedBlockingQueue<>();
            channels.add(channel);
            try {
                if (options.sendEndpoint()) {
                    String endpoint = options.endpointOverride() != null ? options.endpointOverride()
                            : "/messages?sessionId=" + sessionId;
                    writeEvent(out, "endpoint", endpoint);
                }
                out.flush();
                int pushed = 0;
                while (true) {
                    String payload = channel.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    if (payload == null) {
                        if (closeStreams.get()) {
                            break;
                        }
                        out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        continue;
                    }
                    boolean counted = !payload.startsWith(NOISE_MARKER);
                    writeEvent(out, "message", counted ? payload : payload.substring(1));
                    out.flush();
                    if (counted) {
                        pushed++;
                    }
                    if (options.dropAfterMessages() > 0 && pushed >= options.dropAfterMessages()) {
                        break;
                    }
                }
            } catch (IOException e) {
                // 客户端关流：正常现象，不当成夹具错误
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                channels.remove(channel);
                exchange.close();
            }
        } catch (Exception e) {
            errors.add(e.toString());
            try {
                send(exchange, 500, e.toString().getBytes(StandardCharsets.UTF_8), "text/plain");
            } catch (IOException ignored) {
                // 连接已经断了
            }
        }
    }

    private void handleMessage(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "expect POST".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            if (!authorized(exchange)) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8),
                        "application/json");
                return;
            }
            posts.incrementAndGet();
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains(sessionId)) {
                send(exchange, 404, "unknown session".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            if (expireNextPost.compareAndSet(true, false)) {
                send(exchange, 404, "session expired".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.contains("application/json")) {
                send(exchange, 415, "expect application/json".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode message = MAPPER.readTree(raw);
            String method = message.path("method").asText("");
            boolean notification = !message.has("id") || message.get("id").isNull();
            send(exchange, 202, new byte[0], "text/plain");
            if (notification) {
                return;
            }
            BlockingQueue<String> channel = currentChannel();
            if (channel == null) {
                errors.add("没有可用的 SSE 通道，无法推送 " + method + " 的响应");
                return;
            }
            // 真实服务端会在响应前后混入通知与别的请求的响应：客户端必须只认 id 对得上的那条。
            // 噪声带标记：它们不该被计入 dropAfterMessages（否则「推 N 条后断流」这类用例会数错）。
            channel.offer(NOISE_MARKER + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}");
            channel.offer(NOISE_MARKER + "{\"jsonrpc\":\"2.0\",\"id\":999999,\"result\":{\"unrelated\":true}}");
            channel.offer(MAPPER.writeValueAsString(respondFor(method, message)));
        } catch (Exception e) {
            errors.add(e.toString());
            try {
                send(exchange, 500, e.toString().getBytes(StandardCharsets.UTF_8), "text/plain");
            } catch (IOException ignored) {
                // 连接已经断了
            }
        }
    }

    private BlockingQueue<String> currentChannel() {
        synchronized (channels) {
            return channels.isEmpty() ? null : channels.get(channels.size() - 1);
        }
    }

    private boolean authorized(HttpExchange exchange) {
        if (!options.requireToken()) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        // 手动联调便利：token 以 * 结尾表示「前缀匹配」—— 假 OAuth 每下发一次就换一个令牌，
        // 联调时不必每次重启都对齐具体值；自动测试一律用精确匹配。
        String expected = options.token();
        if (authorization != null && expected != null) {
            boolean matches = expected.endsWith("*")
                    ? authorization.startsWith("Bearer " + expected.substring(0, expected.length() - 1))
                    : authorization.equals("Bearer " + expected);
            if (matches) {
                return true;
            }
        }
        rejectedTokens.incrementAndGet();
        return false;
    }

    /** 被拒绝的鉴权次数（测试用：断言「凭证根本没带出去」时看它）。 */
    public int rejectedAuthCount() {
        return rejectedTokens.get();
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
                result.putObject("serverInfo").put("name", "fake-mcp-sse").put("version", "0.1.0");
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
        tools.addObject().put("name", "echo").put("description", "原样回显入参 msg")
                .putObject("inputSchema").put("type", "object");
        tools.addObject().put("name", "boom").put("description", "总是返回 isError=true")
                .putObject("inputSchema").put("type", "object");
        tools.addObject().put("name", "slow").put("description", "睡 5 秒后返回（用于超时测试）")
                .putObject("inputSchema").put("type", "object");
        return result;
    }

    private ObjectNode callTool(JsonNode params) {
        toolCalls.incrementAndGet();
        String name = params.path("name").asText("");
        String argument = params.path("arguments").path("msg").asText("");
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        switch (name) {
            case "echo" -> content.addObject().put("type", "text").put("text", "sse-echo:" + argument);
            case "boom" -> {
                result.put("isError", true);
                content.addObject().put("type", "text").put("text", "sse-boom:" + argument);
            }
            case "slow" -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                content.addObject().put("type", "text").put("text", "slow done");
            }
            default -> {
                result.put("isError", true);
                content.addObject().put("type", "text").put("text", "unknown tool: " + name);
            }
        }
        return result;
    }

    private static void writeEvent(OutputStream out, String event, String data) throws IOException {
        out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
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
        closeStreams.set(true);
        server.stop(0);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18791;
        String path = args.length > 1 ? args[1] : "/sse";
        String token = System.getenv("FAKE_MCP_TOKEN");
        Options options = token == null ? Options.normal() : Options.withToken(token);
        FakeMcpSseServerMain server = new FakeMcpSseServerMain(port, path, options);
        System.out.println("fake-mcp-sse listening on " + server.url()
                + (token == null ? "（无鉴权；设置 FAKE_MCP_TOKEN 可启用 Bearer 校验）"
                        : "（要求 Bearer token" + (token.endsWith("*") ? "，前缀匹配 " + token : "") + "）"));
        Thread.currentThread().join();
    }
}
