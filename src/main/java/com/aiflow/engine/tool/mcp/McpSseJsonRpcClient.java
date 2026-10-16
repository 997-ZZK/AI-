package com.aiflow.engine.tool.mcp;

import com.aiflow.engine.node.RunCanceledException;
import com.aiflow.engine.tool.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 老式 SSE 双端点 MCP 客户端（规范 2024-11-05 的 HTTP+SSE 形态，Claude 配置里的 {@code type: "sse"}）。
 *
 * <p>它跟 Streamable HTTP 的差别不是「换个 URL」，而是**状态机不同**：
 * <ul>
 *   <li>先用 GET 建一条 {@code text/event-stream} 常驻连接，服务端用 {@code event: endpoint} 事件告诉客户端
 *       「消息往哪 POST」—— 端点地址是服务端给的，客户端不能假设</li>
 *   <li>POST 只负责投递（规范回 202 空体），**响应从那条常驻连接上回来**：请求与响应分离，靠 JSON-RPC id 配对</li>
 *   <li>连接断了就是「整条通道断了」：在途请求全部立刻失败，下一条请求重新建流、重新发现端点、重新握手</li>
 * </ul>
 *
 * <p>四件事必须做对，否则要么被绕过、要么泄漏：
 * <ul>
 *   <li><b>端点是服务端给的，属于不可信输入</b>：endpoint 事件里的地址要重新过 {@link SsrfGuard}
 *       （相对路径按 SSE 地址解析）。只校验第一次连接，等于让对方指定我们去连谁</li>
 *   <li><b>读线程必须能退出</b>：close() 关流让读线程自然结束，否则一条挂死的连接会一直占着线程与 socket</li>
 *   <li><b>在途请求要有超时与失败传播</b>：流一断就把 pending 全部判失败，不留下永久等待的 future</li>
 *   <li><b>凭证每次请求都重新取</b>：交给 {@link McpAuth}（OAuth 令牌会过期，静态令牌也可能轮换）</li>
 * </ul>
 *
 * <p>重连策略：**懒重连 + 只重试一次**。流自然死亡不是错误（服务端滚动重启、网关掐空闲连接都会发生），
 * 但「连上就断、断了就连」是配置或鉴权问题的症状，无限重连只会把真问题埋起来。
 */
public final class McpSseJsonRpcClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpSseJsonRpcClient.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    private static final int ERROR_BODY_LIMIT = 400;
    private static final int DETAIL_LIMIT = 240;

    private final McpServerConfig config;
    private final ObjectMapper mapper;
    private final SsrfGuard ssrfGuard;
    private final HttpClient httpClient;
    private final McpAuth auth;
    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private volatile InputStream stream;
    private volatile Thread reader;
    private volatile URI messageEndpoint;
    private volatile CompletableFuture<URI> endpointFuture;
    private volatile boolean ready;
    private volatile boolean closed;
    private JsonNode serverInfo;

    public McpSseJsonRpcClient(McpServerConfig config, ObjectMapper mapper, SsrfGuard ssrfGuard) {
        this(config, mapper, ssrfGuard, System::getenv);
    }

    /** 环境变量查询可注入：测试要覆盖「凭证来自环境」这条路径，又不该污染真实进程环境。 */
    McpSseJsonRpcClient(McpServerConfig config, ObjectMapper mapper, SsrfGuard ssrfGuard,
                        Function<String, String> envLookup) {
        this.config = config;
        this.mapper = mapper;
        this.ssrfGuard = ssrfGuard;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1_000, config.startupTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.auth = McpAuth.resolve(config, envLookup, httpClient, ssrfGuard, mapper);
    }

    @Override
    public String serverId() {
        return config.id();
    }

    @Override
    public McpServerConfig config() {
        return config;
    }

    @Override
    public String describeAuth() {
        return auth.describe();
    }

    /** 通道当前是否活着：诊断用（配额与快照里说明「这条服务端现在能不能用」）。 */
    public boolean connected() {
        return stream != null && ready;
    }

    @Override
    public synchronized void handshake() {
        if (ready) {
            return;
        }
        ensureStream();
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "aiflow-engine");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);
        JsonNode result = exchange("initialize", params, config.startupTimeoutMs());
        String negotiated = result.path("protocolVersion").asText("");
        if (!negotiated.isEmpty() && !PROTOCOL_VERSION.equals(negotiated)) {
            log.info("MCP SSE 服务端 {} 协商协议版本 {}（引擎按 {} 通信，版本向后兼容）",
                    config.id(), negotiated, PROTOCOL_VERSION);
        }
        notify("notifications/initialized", config.startupTimeoutMs());
        serverInfo = result.path("serverInfo");
        ready = true;
    }

    @Override
    public JsonNode serverInfo() {
        return serverInfo == null ? mapper.createObjectNode() : serverInfo;
    }

    @Override
    public JsonNode listTools() {
        return request("tools/list", mapper.createObjectNode(), config.callTimeoutMs());
    }

    @Override
    public JsonNode callTool(String toolName, JsonNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("tools/call", params, config.callTimeoutMs());
    }

    @Override
    public synchronized JsonNode request(String method, JsonNode params, long timeoutMs) {
        if (!"initialize".equals(method) && !ready) {
            handshake();
        }
        try {
            ensureStream();
            return exchange(method, params, timeoutMs);
        } catch (StreamBrokenException e) {
            if ("initialize".equals(method)) {
                throw new McpCallException("MCP SSE 服务端 " + config.id()
                        + " 的通道不可用，initialize 无法完成: " + e.getMessage());
            }
            log.info("MCP SSE 服务端 {} 的通道已断开（{}），重连并重新握手后重试一次", config.id(), e.getMessage());
            ready = false;
            closeStream();
            handshake();
            return exchange(method, params, timeoutMs);
        }
    }

    private JsonNode exchange(String method, JsonNode params, long timeoutMs) {
        long id = nextId.incrementAndGet();
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? mapper.createObjectNode() : params);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            post(message, timeoutMs);
            JsonNode response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return unwrap(method, response);
        } catch (TimeoutException e) {
            throw new McpCallException("MCP SSE 调用超时（" + timeoutMs + " ms，服务端 " + config.id()
                    + "）：消息已投递，但没等到 id=" + id + " 的响应");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StreamBrokenException broken) {
                throw broken;
            }
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 的响应读取失败: "
                    + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        } finally {
            pending.remove(id);
        }
    }

    private JsonNode unwrap(String method, JsonNode response) {
        if (response == null) {
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 的 " + method + " 没有返回结果");
        }
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 调用 " + method + " 返回错误 "
                    + error.path("code").asInt() + ": " + error.path("message").asText(""));
        }
        JsonNode result = response.get("result");
        if (result == null || result.isNull()) {
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 的 " + method + " 响应缺少 result");
        }
        return result;
    }

    /** 建流（幂等）：GET 长连接 + 等第一个 endpoint 事件。 */
    private void ensureStream() {
        if (stream != null) {
            return;
        }
        openStream();
    }

    private void openStream() {
        URI uri = config.url();
        if (ssrfGuard != null) {
            uri = ssrfGuard.validate(uri.toString());
        }
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(config.startupTimeoutMs()))
                    .header("Accept", "text/event-stream")
                    .header(PROTOCOL_HEADER, PROTOCOL_VERSION);
            auth.apply(builder);
            request = builder.GET().build();
        } catch (IllegalArgumentException e) {
            throw new McpCallException("MCP SSE 服务端 " + config.id()
                    + " 的建流请求不合法（不回显头部值）: " + e.getMessage());
        }
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 建流超时（"
                    + config.startupTimeoutMs() + " ms）：该地址可能不是长连接端点");
        } catch (IOException e) {
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 建流失败（网络层）: "
                    + messageOf(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        }
        int status = response.statusCode();
        InputStream body = response.body();
        if (status >= 400) {
            String detail = readBody(body);
            closeQuietly(body);
            String hint = status == 401 || status == 403
                    ? "（鉴权失败：检查该服务端的 authTokenEnv / oauth / headers 里的环境变量是否已设置，值只从环境读）"
                    : "";
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 返回 HTTP " + status + hint
                    + (detail.isEmpty() ? "" : "：" + detail));
        }
        String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        if (!contentType.contains("text/event-stream")) {
            closeQuietly(body);
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 的 GET 响应不是 text/event-stream"
                    + "（实际 content-type=" + contentType + "）：老式 SSE 服务端必须用长连接下发事件");
        }
        CompletableFuture<URI> discovered = new CompletableFuture<>();
        this.stream = body;
        this.endpointFuture = discovered;
        this.reader = Thread.ofVirtual().name("mcp-" + config.id() + "-sse").start(() -> readLoop(body, discovered));
        try {
            URI endpoint = discovered.get(config.startupTimeoutMs(), TimeUnit.MILLISECONDS);
            if (endpoint == null) {
                throw new McpCallException("MCP SSE 服务端 " + config.id() + " 下发的端点为空白");
            }
            this.messageEndpoint = endpoint;
        } catch (TimeoutException e) {
            closeStream();
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 在 " + config.startupTimeoutMs()
                    + " ms 内没有下发 endpoint 事件（这个地址是老式 SSE 服务端吗？单端点服务端请用 transport: http）");
        } catch (ExecutionException e) {
            String detail = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            closeStream();
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 的通道在发现端点前就断了: " + detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeStream();
            throw new RunCanceledException();
        }
    }

    /** 读线程：解析 SSE 事件流。事件只有两种有意义：endpoint（发现投递地址）与 message（响应回来）。 */
    private void readLoop(InputStream body, CompletableFuture<URI> discovered) {
        String reason = "通道已关闭";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String eventName = "";
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    dispatch(eventName, data, discovered);
                    eventName = "";
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = stripLeadingSpace(line.substring(6));
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append("\n");
                    }
                    data.append(stripLeadingSpace(line.substring(5)));
                }
            }
            dispatch(eventName, data, discovered);
            reason = "服务端关闭了 SSE 流";
        } catch (IOException e) {
            reason = "SSE 流读取失败: " + messageOf(e);
        } catch (RuntimeException e) {
            reason = "SSE 流处理失败: " + e.getMessage();
        } finally {
            if (stream == body) {
                stream = null;
                messageEndpoint = null;
            }
            ready = false;
            if (!discovered.isDone()) {
                discovered.completeExceptionally(new StreamBrokenException(reason));
            }
            failPending(reason);
            if (!closed) {
                log.info("MCP SSE 服务端 {} 的通道已断开：{}（下一条消息会重连）", config.id(), reason);
            }
        }
    }

    private void dispatch(String eventName, StringBuilder data, CompletableFuture<URI> discovered) {
        if (data.isEmpty()) {
            return;
        }
        String payload = data.toString();
        data.setLength(0);
        switch (eventName) {
            case "endpoint" -> {
                URI endpoint = resolveEndpoint(payload);
                messageEndpoint = endpoint;
                discovered.complete(endpoint);
            }
            case "message" -> handleMessage(payload);
            default -> log.debug("MCP SSE 服务端 {} 的事件 {} 与本次会话无关（忽略）: {}",
                    config.id(), eventName, abbreviate(payload));
        }
    }

    /** 端点地址来自服务端：相对路径按建流地址解析，然后**重新**过一遍出网白名单。 */
    private URI resolveEndpoint(String payload) {
        String value = payload.strip();
        if (value.isEmpty()) {
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 下发的 endpoint 事件为空");
        }
        URI resolved = config.url().resolve(value);
        if (resolved == null) {
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 下发的端点无法解析: " + abbreviate(value));
        }
        String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 下发的端点协议不受支持: "
                    + (scheme.isEmpty() ? "(空)" : scheme) + "（只允许 http/https）");
        }
        if (resolved.getHost() == null || resolved.getHost().isBlank()) {
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 下发的端点缺少 host: " + abbreviate(value));
        }
        return ssrfGuard == null ? resolved : ssrfGuard.validate(resolved.toString());
    }

    private void handleMessage(String payload) {
        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (IOException e) {
            log.debug("MCP SSE 服务端 {} 的消息不是合法 JSON（忽略）: {}", config.id(), abbreviate(payload));
            return;
        }
        JsonNode idNode = node.get("id");
        if (idNode == null || idNode.isNull()) {
            log.debug("MCP SSE 服务端 {} 推来的通知与当前请求无关（忽略）: {}", config.id(), abbreviate(payload));
            return;
        }
        CompletableFuture<JsonNode> future = pending.get(idNode.asLong());
        if (future == null) {
            log.debug("MCP SSE 服务端 {} 推来的 id={} 没有对应请求（忽略）", config.id(), idNode.asLong());
            return;
        }
        future.complete(node);
    }

    /** 投递一条消息：POST 到发现出来的端点，响应走 SSE，所以这里只关心「有没有投递成功」。 */
    private void post(JsonNode message, long timeoutMs) {
        URI uri = messageEndpoint;
        if (uri == null) {
            throw new StreamBrokenException("端点尚未发现");
        }
        if (ssrfGuard != null) {
            uri = ssrfGuard.validate(uri.toString());
        }
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header(PROTOCOL_HEADER, PROTOCOL_VERSION);
            auth.apply(builder);
            request = builder.POST(HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(message), StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException e) {
            throw new McpCallException("MCP SSE 服务端 " + config.id()
                    + " 的消息请求不合法（不回显头部值）: " + e.getMessage());
        } catch (IOException e) {
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 的消息序列化失败: "
                    + messageOf(e), e);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new McpCallException("MCP SSE 投递超时（" + timeoutMs + " ms，服务端 " + config.id() + "）");
        } catch (IOException e) {
            throw new StreamBrokenException("消息端点请求失败（网络层）: " + messageOf(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        }
        int status = response.statusCode();
        if (status == 404 || status == 410) {
            throw new StreamBrokenException("消息端点返回 HTTP " + status + "（会话已失效）");
        }
        if (status >= 400) {
            String detail = abbreviate(response.body());
            String hint = status == 401 || status == 403
                    ? "（鉴权失败：检查该服务端的 authTokenEnv / oauth / headers 里的环境变量是否已设置）"
                    : "";
            throw new McpCallException("MCP SSE 服务端 " + config.id() + " 的消息端点返回 HTTP " + status
                    + hint + (detail.isEmpty() ? "" : "：" + detail));
        }
    }

    private void notify(String method, long timeoutMs) {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", mapper.createObjectNode());
        try {
            post(message, timeoutMs);
        } catch (RuntimeException e) {
            log.warn("MCP SSE 服务端 {} 的通知 {} 发送失败（服务端可能仍可正常工作）: {}",
                    config.id(), method, e.getMessage());
        }
    }

    private void failPending(String reason) {
        if (pending.isEmpty()) {
            return;
        }
        StreamBrokenException failure = new StreamBrokenException(reason);
        pending.forEach((id, future) -> future.completeExceptionally(failure));
    }

    @Override
    public synchronized void close() {
        closed = true;
        ready = false;
        closeStream();
        auth.close();
    }

    /** 关流就够：读线程在 read() 上被解开，随后自己走完 finally。 */
    private void closeStream() {
        InputStream current = stream;
        stream = null;
        messageEndpoint = null;
        endpointFuture = null;
        reader = null;
        if (current != null) {
            closeQuietly(current);
        }
    }

    private static String stripLeadingSpace(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String readBody(InputStream stream) {
        try {
            byte[] bytes = stream.readNBytes(ERROR_BODY_LIMIT);
            return abbreviate(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("关闭 MCP SSE 流失败: {}", e.getMessage());
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.lines().map(String::strip).filter(part -> !part.isEmpty())
                .collect(Collectors.joining(" "));
        return flat.length() <= DETAIL_LIMIT ? flat : flat.substring(0, DETAIL_LIMIT) + "…";
    }

    /** 通道断了：不是「这次调用失败」而是「整条链路不可用」，调用方据此决定要不要重连重试。 */
    private static final class StreamBrokenException extends RuntimeException {

        private StreamBrokenException(String message) {
            super(message);
        }
    }
}
