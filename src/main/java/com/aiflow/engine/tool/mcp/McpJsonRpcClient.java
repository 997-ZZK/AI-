package com.aiflow.engine.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个 MCP 服务端的 stdio 客户端：newline-delimited JSON-RPC 2.0。
 *
 * <p>三个实现上的选择，都是被 MCP 的实际形态逼出来的：
 * <ul>
 *   <li><b>stderr 单独抽干并留尾部若干行</b>：MCP 服务端的日志走 stderr，不读就会把管道写满、进程卡死；
 *       而失败时最有用的诊断信息恰好就在那几行里（例如「模型名不存在」），所以留在错误消息里而不是丢进黑洞</li>
 *   <li><b>超时即杀进程</b>：晚到的响应会和后续请求的 id 错位，留下一个「还活着但答非所问」的进程比杀掉更危险；
 *       下一次调用会自动重启，代价只是一次握手</li>
 *   <li><b>每个服务端串行发请求</b>：MCP 服务端多为单线程实现，并发压上去只会把超时误判成故障。
 *       引擎里一次 run 的节点执行本来就是串行的，这里不构成瓶颈</li>
 * </ul>
 */
public final class McpJsonRpcClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpJsonRpcClient.class);
    private static final int STDERR_TAIL_LINES = 12;
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpServerConfig config;
    private final ObjectMapper mapper;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong();

    private Process process;
    private BufferedWriter writer;
    private boolean ready;
    private JsonNode serverInfo;

    public McpJsonRpcClient(McpServerConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public String serverId() {
        return config.id();
    }

    @Override
    public McpServerConfig config() {
        return config;
    }

    /** 握手（含 {@code notifications/initialized}），已握手且进程存活时是空操作。 */
    @Override
    public synchronized void handshake() {
        if (ready && isAlive()) {
            return;
        }
        if (!isAlive()) {
            startProcess();
        }
        ready = false;
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
        notify("notifications/initialized");
        serverInfo = result.path("serverInfo");
        ready = true;
    }

    @Override
    public JsonNode serverInfo() {
        return serverInfo == null ? mapper.createObjectNode() : serverInfo;
    }

    @Override
    public synchronized JsonNode listTools() {
        return request("tools/list", mapper.createObjectNode(), config.callTimeoutMs());
    }

    @Override
    public synchronized JsonNode callTool(String toolName, JsonNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("tools/call", params, config.callTimeoutMs());
    }

    @Override
    public synchronized JsonNode request(String method, JsonNode params, long timeoutMs) {
        if (!"initialize".equals(method) && (!ready || !isAlive())) {
            handshake();
        }
        long id = nextId.incrementAndGet();
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? mapper.createObjectNode() : params);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeLine(message);
        } catch (IOException e) {
            pending.remove(id);
            stopProcess();
            throw new McpCallException("MCP 服务端 " + config.id() + " 写入失败: " + e.getMessage() + stderrHint(), e);
        }
        try {
            JsonNode response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            JsonNode error = response.get("error");
            if (error != null && !error.isNull()) {
                throw new McpCallException("MCP 服务端 " + config.id() + " 调用 " + method + " 返回错误 "
                        + error.path("code").asInt() + ": " + error.path("message").asText("") + stderrHint());
            }
            JsonNode result = response.get("result");
            if (result == null || result.isNull()) {
                throw new McpCallException("MCP 服务端 " + config.id() + " 的 " + method + " 响应缺少 result");
            }
            return result;
        } catch (TimeoutException e) {
            stopProcess();
            throw new McpCallException("MCP 调用 " + method + " 超时（" + timeoutMs + " ms，服务端 " + config.id()
                    + "，进程已回收，下次调用重新握手）" + stderrHint());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopProcess();
            throw new McpCallException("MCP 调用 " + method + " 被中断（服务端 " + config.id() + "）", e);
        } catch (ExecutionException e) {
            stopProcess();
            throw new McpCallException("MCP 调用 " + method + " 失败（服务端 " + config.id() + "）: "
                    + e.getCause().getMessage() + stderrHint(), e.getCause());
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method) {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", mapper.createObjectNode());
        try {
            writeLine(message);
        } catch (IOException e) {
            log.debug("MCP 服务端 {} 通知 {} 发送失败: {}", config.id(), method, e.getMessage());
        }
    }

    private void writeLine(JsonNode message) throws IOException {
        if (writer == null) {
            throw new IOException("MCP 服务端未启动");
        }
        writer.write(mapper.writeValueAsString(message));
        writer.write('\n');
        writer.flush();
    }

    private boolean isAlive() {
        return process != null && process.isAlive();
    }

    private void startProcess() {
        List<String> command = new ArrayList<>();
        command.add(config.command());
        command.addAll(config.args());
        ProcessBuilder builder = new ProcessBuilder(command);
        if (config.workDir() != null) {
            builder.directory(config.workDir().toFile());
        }
        Map<String, String> environment = builder.environment();
        for (String name : config.passThroughEnv()) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        environment.putAll(config.env());
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new McpCallException("MCP 服务端 " + config.id() + " 启动失败（" + command.get(0) + "）: "
                    + e.getMessage(), e);
        }
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        pumpStdout(process);
        pumpStderr(process);
        log.info("MCP 服务端 {} 已启动: {}", config.id(), String.join(" ", command));
    }

    private void pumpStdout(Process target) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(target.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node;
                    try {
                        node = mapper.readTree(line);
                    } catch (IOException e) {
                        log.debug("MCP 服务端 {} 输出非 JSON 行（忽略）: {}", config.id(), abbreviate(line));
                        continue;
                    }
                    JsonNode idNode = node.get("id");
                    if (idNode == null || idNode.isNull()) {
                        continue;
                    }
                    CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
                    if (future != null) {
                        future.complete(node);
                    }
                }
            } catch (IOException e) {
                log.debug("MCP 服务端 {} stdout 读取结束: {}", config.id(), e.getMessage());
            } finally {
                for (CompletableFuture<JsonNode> future : pending.values()) {
                    future.completeExceptionally(new IllegalStateException("MCP 服务端进程已退出"));
                }
                pending.clear();
            }
        }, "mcp-" + config.id() + "-stdout");
        thread.setDaemon(true);
        thread.start();
    }

    private void pumpStderr(Process target) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(target.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrTail) {
                        stderrTail.addLast(abbreviate(line));
                        while (stderrTail.size() > STDERR_TAIL_LINES) {
                            stderrTail.removeFirst();
                        }
                    }
                    log.debug("[mcp:{}] {}", config.id(), line);
                }
            } catch (IOException e) {
                log.debug("MCP 服务端 {} stderr 读取结束: {}", config.id(), e.getMessage());
            }
        }, "mcp-" + config.id() + "-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private String stderrHint() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "";
            }
            return "；stderr 末 " + stderrTail.size() + " 行: " + String.join(" | ", stderrTail);
        }
    }

    private static String abbreviate(String line) {
        String flat = line.strip().replaceAll("\\s+", " ");
        return flat.length() <= 240 ? flat : flat.substring(0, 240) + "…";
    }

    private void stopProcess() {
        ready = false;
        writer = null;
        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    @Override
    public synchronized void close() {
        stopProcess();
    }
}
