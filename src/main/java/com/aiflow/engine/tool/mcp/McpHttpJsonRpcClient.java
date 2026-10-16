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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 一个 MCP 服务端的 Streamable HTTP 客户端：单端点 POST，响应可以是 JSON，也可以是一条 SSE 流。
 *
 * <p>与 {@link McpJsonRpcClient} 的差别只有「消息怎么走」，所以两者实现同一个 {@link McpClient}，
 * 上层装配逻辑不再分叉。HTTP 形态特有的四件事：
 * <ul>
 *   <li><b>每次请求都过一次 {@link SsrfGuard}</b>：注册时那次只能证明「配置时」是安全的，域名解析结果会变，
 *       所以出网白名单与内网地址检查要落在真正发请求的那一刻（与 {@code HttpToolInvoker} 一致）</li>
 *   <li><b>会话 id</b>：服务端在响应头 {@code Mcp-Session-Id} 下发，后续请求要回带；服务端回 404 表示会话已失效，
 *       这里按规范重新握手并**重试一次** —— 只重试一次，因为「一直失效」说明是配置或鉴权问题，重试掩盖不了</li>
 *   <li><b>SSE 响应解析</b>：一个事件可能由多行 {@code data:} 组成，中间还会混注释行（心跳）与无关事件，
 *       都要跳过；只有 id 对得上的那条才算本次响应。整个读取带超时，超时就关流 —— 否则读线程会一直挂着</li>
 *   <li><b>凭证只从环境变量来</b>：静态令牌走 {@code authTokenEnv} 或 headers 值里的 {@code ${VAR}}，
 * </ul>
 *
 * <p>不跟随重定向，理由与 HTTP 工具相同：跟随等于把「白名单校验过的地址」换成「对方指定的地址」。
 */
public final class McpHttpJsonRpcClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpHttpJsonRpcClient.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    private static final int ERROR_BODY_LIMIT = 400;
    private static final int BODY_LIMIT = 65536;
    private static final int SESSION_ID_LIMIT = 256;
    private static final int DETAIL_LIMIT = 240;

    private final McpServerConfig config;
    private final ObjectMapper mapper;
    private final SsrfGuard ssrfGuard;
    private final HttpClient httpClient;
    private final McpAuth auth;
    private final AtomicLong nextId = new AtomicLong();

    private String sessionId;
    private boolean ready;
    private JsonNode serverInfo;

    public McpHttpJsonRpcClient(McpServerConfig config, ObjectMapper mapper, SsrfGuard ssrfGuard) {
        this(config, mapper, ssrfGuard, System::getenv);
    }

    /** 环境变量查询做成可注入的：测试要能在不污染真实进程环境的前提下覆盖「凭证来自环境」这条路径。 */
    McpHttpJsonRpcClient(McpServerConfig config, ObjectMapper mapper, SsrfGuard ssrfGuard,
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

    @Override
    public synchronized void handshake() {
        if (ready) {
            return;
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "aiflow-engine");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);
        JsonNode result = request("initialize", params, config.startupTimeoutMs());
        String negotiated = result.path("protocolVersion").asText("");
        if (!negotiated.isEmpty() && !PROTOCOL_VERSION.equals(negotiated)) {
            log.info("MCP 服务端 {} 协商协议版本 {}（引擎按 {} 通信，版本向后兼容）",
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
            return requestOnce(method, params, timeoutMs);
        } catch (SessionExpiredException e) {
            if ("initialize".equals(method)) {
                throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 拒绝了 initialize（HTTP 404）："
                        + "检查 url 是否指向 MCP 端点", e);
            }
            log.info("MCP HTTP 服务端 {} 的会话已失效，重新握手后重试一次", config.id());
            sessionId = null;
            ready = false;
            handshake();
            return requestOnce(method, params, timeoutMs);
        }
    }

    private JsonNode requestOnce(String method, JsonNode params, long timeoutMs) {
        long id = nextId.incrementAndGet();
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? mapper.createObjectNode() : params);
        JsonNode response = exchange(message, id, timeoutMs);
        if (response == null) {
            throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 的 " + method
                    + " 没有返回响应体（服务端返回了 202/204）");
        }
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 调用 " + method + " 返回错误 "
                    + error.path("code").asInt() + ": " + error.path("message").asText(""));
        }
        JsonNode result = response.get("result");
        if (result == null || result.isNull()) {
            throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 的 " + method + " 响应缺少 result");
        }
        return result;
    }

    /** 发一条 JSON-RPC 消息（id 为负表示通知）。返回 null 表示服务端接受了消息但没有响应体。 */
    private JsonNode exchange(JsonNode message, long id, long timeoutMs) {
        HttpRequest request = buildRequest(message, timeoutMs);
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new McpCallException("MCP HTTP 调用超时（" + timeoutMs + " ms，服务端 " + config.id()
                    + "，连接已断开；HTTP 形态没有进程可回收，下一条消息从头开始）");
        } catch (IOException e) {
            throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 请求失败（网络层）: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        }
        try (InputStream stream = response.body()) {
            int status = response.statusCode();
            captureSession(response);
            if (status >= 400) {
                throw failure(status, stream);
            }
            if (id < 0 || status == 202 || status == 204) {
                return null;
            }
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (contentType.contains("text/event-stream")) {
                return readSseForId(stream, id, timeoutMs);
            }
            byte[] bytes = stream.readNBytes(BODY_LIMIT + 1);
            if (bytes.length == 0) {
                throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 返回了空响应体");
            }
            if (bytes.length > BODY_LIMIT) {
                throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 的响应超过 " + BODY_LIMIT + " 字节上限");
            }
            try {
                return mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 的响应不是合法 JSON: "
                        + abbreviate(new String(bytes, StandardCharsets.UTF_8)), e);
            }
        } catch (IOException e) {
            throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 读取响应失败: " + e.getMessage(), e);
        }
    }

    private HttpRequest buildRequest(JsonNode message, long timeoutMs) {
        URI uri = config.url();
        if (ssrfGuard != null) {
            uri = ssrfGuard.validate(uri.toString());
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(message), StandardCharsets.UTF_8));
            auth.apply(builder);
            if (sessionId != null) {
                builder.header(SESSION_HEADER, sessionId);
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 的请求头不合法（不回显头部值）: "
                    + e.getMessage());
        } catch (IOException e) {
            throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 的请求序列化失败: " + e.getMessage(), e);
        }
    }

    private JsonNode readSseForId(InputStream stream, long id, long timeoutMs) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        Thread.ofVirtual().name("mcp-" + config.id() + "-sse").start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        JsonNode matched = dispatch(data, id);
                        if (matched != null) {
                            future.complete(matched);
                            return;
                        }
                        continue;
                    }
                    if (line.startsWith(":")) {
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        String chunk = line.substring(5);
                        if (chunk.startsWith(" ")) {
                            chunk = chunk.substring(1);
                        }
                        if (!data.isEmpty()) {
                            data.append("\n");
                        }
                        data.append(chunk);
                    }
                }
                JsonNode matched = dispatch(data, id);
                if (matched != null) {
                    future.complete(matched);
                    return;
                }
                future.completeExceptionally(new IllegalStateException(
                        "SSE 流已结束但没有收到 id=" + id + " 的响应（服务端可能只把它发给了另一个连接）"));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            closeQuietly(stream);
            throw new McpCallException("MCP HTTP 调用超时（" + timeoutMs + " ms，服务端 " + config.id()
                    + "，连接已断开；HTTP 形态没有进程可回收，下一条消息从头开始）");
        } catch (ExecutionException e) {
            closeQuietly(stream);
            throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 的响应流读取失败: "
                    + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(stream);
            throw new RunCanceledException();
        }
    }

    /** 处理一条 SSE 事件：只认 id 对得上的那条，其余（心跳、别的请求、通知）跳过并留痕。 */
    private JsonNode dispatch(StringBuilder data, long id) {
        if (data.isEmpty()) {
            return null;
        }
        String payload = data.toString();
        data.setLength(0);
        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (IOException e) {
            log.debug("MCP HTTP 服务端 {} 的 SSE 事件不是合法 JSON（忽略）: {}", config.id(), abbreviate(payload));
            return null;
        }
        JsonNode idNode = node.get("id");
        if (idNode != null && !idNode.isNull() && idNode.asLong() == id) {
            return node;
        }
        log.debug("MCP HTTP 服务端 {} 的 SSE 事件与当前请求无关（忽略）: {}", config.id(), abbreviate(payload));
        return null;
    }

    private void captureSession(HttpResponse<?> response) {
        String value = response.headers().firstValue(SESSION_HEADER).orElse(null);
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.strip();
        boolean printableAscii = trimmed.chars().allMatch(c -> c >= 0x21 && c <= 0x7E);
        if (trimmed.length() > SESSION_ID_LIMIT || !printableAscii) {
            throw new McpCallException("MCP HTTP 服务端 " + config.id() + " 下发的 " + SESSION_HEADER
                    + " 非法（长度或字符集越界），已拒绝使用");
        }
        sessionId = trimmed;
    }

    private RuntimeException failure(int status, InputStream stream) {
        String detail = readBody(stream);
        if (status == 404 && sessionId != null) {
            return new SessionExpiredException("会话已失效");
        }
        String hint = status == 401 || status == 403
                ? "（鉴权失败：检查该服务端的 authTokenEnv 或 headers 里的环境变量是否已设置，注意值只从环境读）"
                : "";
        return new McpCallException("MCP HTTP 服务端 " + config.id() + " 返回 HTTP " + status + hint
                + (detail.isEmpty() ? "" : "：" + detail));
    }

    private static String readBody(InputStream stream) {
        try {
            byte[] bytes = stream.readNBytes(ERROR_BODY_LIMIT);
            return abbreviate(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        }
    }

    private void notify(String method, long timeoutMs) {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", mapper.createObjectNode());
        try {
            exchange(message, -1, timeoutMs);
        } catch (RuntimeException e) {
            log.warn("MCP HTTP 服务端 {} 通知 {} 发送失败（服务端可能仍可正常工作）: {}",
                    config.id(), method, e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        ready = false;
        auth.close();
        String current = sessionId;
        sessionId = null;
        if (current == null) {
            return;
        }
        // 规范建议客户端主动 DELETE 终止会话：异步发出、不阻塞关闭流程，失败了也只记 debug。
        try {
            URI uri = config.url();
            if (ssrfGuard != null) {
                uri = ssrfGuard.validate(uri.toString());
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .header(SESSION_HEADER, current)
                    .DELETE()
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(error -> null);
        } catch (RuntimeException e) {
            log.debug("MCP HTTP 服务端 {} 的会话终止请求未发出: {}", config.id(), e.getMessage());
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("关闭 MCP 响应流失败: {}", e.getMessage());
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

    /** 服务端回了 404：会话过期。它不该被当成普通失败，也不该一路重试。 */
    private static final class SessionExpiredException extends RuntimeException {

        private SessionExpiredException(String message) {
            super(message);
        }
    }
}
