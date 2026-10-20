package com.aiflow.engine.tool.mcp;

import com.aiflow.engine.common.Hashes;
import com.aiflow.engine.tool.SsrfGuard;
import com.aiflow.engine.tool.ToolUrlSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 服务端生命周期 + 工具发现（stdio / Streamable HTTP / 老式 SSE 三种传输共用这一套装配逻辑）。
 *
 * <p>启动时逐个握手并 {@code tools/list}：**单个服务端起不来不能拖垮整个应用**。这里的取舍与 provider 探活一致 ——
 * 起不来的服务端记进 {@code failures}（能力接口/日志里能看见原因），工具不注册，引擎照常启动。
 * 反过来，如果让一个坏配置把 Spring 上下文炸掉，用户连控制台都进不去，连「哪条配置写错了」都看不到。
 *
 * <p>远程形态（HTTP / SSE）在注册前先过一次 {@link SsrfGuard}：让「配了个不在白名单的地址」在 {@code /api/v1/tools}
 * 里当场可见，而不是等到某个 run 跑到一半才失败。真正的请求还会再过一次（DNS 会变，见客户端注释）。
 *
 * <p><b>热更新一致性</b>（{@link #reload()}）由三件事构成：
 * <ol>
 *   <li><b>快照带指纹</b>：每个工具的「名字 + 描述 + 入参 schema」算一个指纹，服务端也有一份版本号。
 *       运行期间调用工具前，会拿注册表里那条快照和当前快照比对 —— 不一致就**明确失败**，
 *       而不是按旧定义去调一个新工具（那是最难查的一类线上问题）</li>
 *   <li><b>先换后关</b>：新客户端装配完成才关闭旧客户端，期间旧客户端上的调用仍能跑完</li>
 *   <li><b>drain 有上限</b>：等待在途调用结束，但最多等 {@code aiflow.tools.mcp.drain-timeout-ms}；
 *       超时就强关并留 WARN —— 一次热更新不该被一个卡死的调用无限期拖住</li>
 * </ol>
 */
@Component
public class McpServerManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);
    private static final long DRAIN_POLL_NANOS = 25_000_000L;
    private static final Pattern URI_TOKEN = Pattern.compile("(?i)https?://[^\\s\\]\\[\\)\\}\\\",;]+(?:\\?[^\\s\\]\\[\\)\\}\\\",;]*)?");

    private final McpToolProperties properties;
    private final McpConfigImporter importer;
    private final ObjectMapper mapper;
    private final SsrfGuard ssrfGuard;
    private final Map<String, ManagedClient> clients = new LinkedHashMap<>();
    private final List<McpToolDescriptor> descriptors = new ArrayList<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    @Autowired
    public McpServerManager(McpToolProperties properties, McpConfigImporter importer, ObjectMapper mapper,
                            SsrfGuard ssrfGuard) {
        this.properties = properties;
        this.importer = importer;
        this.mapper = mapper;
        this.ssrfGuard = ssrfGuard;
    }

    /** 只给 stdio 用例的便利构造：没有 SsrfGuard 时远程形态会被拒绝装配（而不是被放行）。 */
    McpServerManager(McpToolProperties properties, McpConfigImporter importer, ObjectMapper mapper) {
        this(properties, importer, mapper, null);
    }

    @PostConstruct
    public void start() {
        if (!properties.enabled()) {
            log.info("MCP 工具未启用（aiflow.tools.mcp.enabled=false），跳过服务端装配");
            return;
        }
        reload();
    }

    /**
     * 重新读配置并重建全部连接。改完 yml 或导入文件后调它即可，不必重启应用。
     *
     * <p>顺序是「先装新的、再关旧的」：旧客户端在 drain 期间仍然可用，正在跑的 run 不会被一次热更新腰斩。
     */
    public synchronized void reload() {
        Map<String, ManagedClient> previous = new LinkedHashMap<>(clients);
        clients.clear();
        descriptors.clear();
        failures.clear();
        for (McpServerConfig config : resolveServers()) {
            register(config);
        }
        long current = generation.incrementAndGet();
        closeDrained(previous.values());
        log.info("MCP 工具装配完成（generation={}）: {} 个工具来自 {} 个服务端{}", current, descriptors.size(),
                clients.size(), failures.isEmpty() ? "" : "，失败 " + failures.keySet());
    }

    private List<McpServerConfig> resolveServers() {
        List<McpServerConfig> servers = new ArrayList<>();
        for (McpToolProperties.Server server : properties.servers()) {
            if (!server.enabled()) {
                log.info("MCP 服务端 {} 已在配置里禁用，跳过", server.id());
                continue;
            }
            boolean hasCommand = server.command() != null && !server.command().isBlank();
            boolean hasUrl = server.url() != null && !server.url().isBlank();
            if (hasCommand && hasUrl) {
                failures.put(server.id(), "配置同时给了 command 与 url，无法判断传输形态（二选一；确需指定用 transport）");
                log.warn("MCP 服务端 {} 配置非法：command 与 url 同时存在", server.id());
                continue;
            }
            McpTransport declared = McpTransport.parse(server.transport(), server.id());
            McpTransport transport = declared != null ? declared
                    : (hasUrl ? McpTransport.HTTP : (hasCommand ? McpTransport.STDIO : null));
            if (transport == null) {
                failures.put(server.id(), "配置缺少 command 或 url（前者 = stdio，后者 = 远程；也可以用 transport 明确指定）");
                log.warn("MCP 服务端 {} 配置非法：既没有 command 也没有 url", server.id());
                continue;
            }
            try {
                servers.add(switch (transport) {
                    case STDIO -> stdioConfig(server);
                    case HTTP, SSE -> remoteConfig(server, transport);
                });
            } catch (RuntimeException e) {
                String reason = safeFailure(e.getMessage(), server.url());
                failures.put(server.id(), reason);
                log.warn("MCP 服务端 {} 配置无法使用：{}", server.id(), reason);
            }
        }
        try {
            servers.addAll(importer.load(properties));
        } catch (RuntimeException e) {
            // 一个导入文件里的坏端点不能让整个 manager 的 reload 失败；同时不能把导入配置中的
            // userinfo/query/fragment 带进 /api/v1/tools 的 failures。
            String reason = safeFailure(e.getMessage(), (String) null);
            failures.put("import", reason);
            log.warn("MCP 导入配置无法使用：{}", reason);
        }
        Map<String, McpServerConfig> deduped = new LinkedHashMap<>();
        for (McpServerConfig server : servers) {
            if (deduped.putIfAbsent(server.id(), server) != null) {
                log.warn("MCP 服务端 id 重复，后者被忽略: {}（来源 {}）", server.id(), server.source());
            }
        }
        return List.copyOf(deduped.values());
    }

    private McpServerConfig stdioConfig(McpToolProperties.Server server) {
        return McpServerConfig.stdio(server.id(), server.command(), server.args(), server.env(),
                server.passThroughEnv(),
                server.workDir() == null || server.workDir().isBlank() ? null : java.nio.file.Path.of(server.workDir()),
                server.startupTimeoutMs() == null ? properties.startupTimeoutMs() : server.startupTimeoutMs(),
                server.callTimeoutMs() == null ? properties.callTimeoutMs() : server.callTimeoutMs(),
                server.allowedTools(), "config");
    }

    private McpServerConfig remoteConfig(McpToolProperties.Server server, McpTransport transport) {
        long startup = server.startupTimeoutMs() == null ? properties.startupTimeoutMs() : server.startupTimeoutMs();
        long call = server.callTimeoutMs() == null ? properties.callTimeoutMs() : server.callTimeoutMs();
        McpServerConfig.OAuth oauth = oauthOf(server);
        return transport == McpTransport.SSE
                ? McpServerConfig.sse(server.id(), server.url(), server.headers(), server.authTokenEnv(), oauth,
                        startup, call, server.allowedTools(), "config")
                : McpServerConfig.http(server.id(), server.url(), server.headers(), server.authTokenEnv(), oauth,
                        startup, call, server.allowedTools(), "config");
    }

    private static McpServerConfig.OAuth oauthOf(McpToolProperties.Server server) {
        McpToolProperties.OAuth oauth = server.oauth();
        if (oauth == null) {
            return null;
        }
        return new McpServerConfig.OAuth(oauth.tokenUrl(), oauth.clientIdEnv(), oauth.clientSecretEnv(),
                oauth.refreshTokenEnv(), oauth.scope(), oauth.extraParams(), oauth.earlyRefreshSeconds());
    }

    private void register(McpServerConfig config) {
        ManagedClient managed;
        try {
            managed = newManagedClient(config);
        } catch (RuntimeException e) {
            String reason = safeFailure(e.getMessage(), config.url());
            failures.put(config.id(), reason);
            log.warn("MCP 服务端 {} 不可用，跳过：{}", config.id(), reason);
            return;
        }
        try {
            managed.client.handshake();
            JsonNode tools = managed.client.listTools().path("tools");
            List<McpToolDescriptor> discovered = new ArrayList<>();
            for (JsonNode tool : tools) {
                String name = tool.path("name").asText("");
                if (name.isEmpty()) {
                    continue;
                }
                if (!config.allows(name)) {
                    log.info("MCP 服务端 {} 的工具 {} 不在白名单内，跳过", config.id(), name);
                    continue;
                }
                discovered.add(new McpToolDescriptor(config.id(), name,
                        tool.path("description").asText(""),
                        tool.has("inputSchema") ? tool.get("inputSchema") : mapper.createObjectNode()));
            }
            if (discovered.isEmpty()) {
                failures.put(config.id(), "服务端可用但没有可注册的工具（检查 allowedTools 白名单）");
                managed.client.close();
                return;
            }
            managed.tools = List.copyOf(discovered);
            managed.version = versionOf(config, discovered);
            descriptors.addAll(discovered);
            clients.put(config.id(), managed);
            log.info("MCP 服务端 {}（{}）注册 {} 个工具: {}（version={}）", config.id(),
                    config.transport().wireName(), discovered.size(),
                    discovered.stream().map(McpToolDescriptor::name).toList(), managed.version);
        } catch (RuntimeException e) {
            String reason = safeFailure(e.getMessage(), config.url());
            failures.put(config.id(), reason);
            log.warn("MCP 服务端 {} 不可用，跳过：{}", config.id(), reason);
            managed.client.close();
        }
    }

    /** 服务端版本 = 传输 + id + 全部工具指纹（工具增删改都会变）。用于热更新前后的一致性判断与排障。 */
    private static String versionOf(McpServerConfig config, List<McpToolDescriptor> tools) {
        String material = config.transport().wireName() + "|" + config.id() + "|";
        String fingerprints = tools.stream().map(McpToolDescriptor::fingerprint).sorted()
                .reduce("", (left, right) -> left + "," + right);
        return Hashes.sha256Hex(material + fingerprints).substring(0, 12);
    }

    private ManagedClient newManagedClient(McpServerConfig config) {
        McpClient client = switch (config.transport()) {
            case STDIO -> new McpJsonRpcClient(config, mapper);
            case HTTP -> new McpHttpJsonRpcClient(config, mapper, requireSsrfGuard(config));
            case SSE -> new McpSseJsonRpcClient(config, mapper, requireSsrfGuard(config));
        };
        // 注册时先校验一次：白名单/内网地址这类问题在 /api/v1/tools 里当场可见，而不是等某次 run 失败。
        if (config.transport().remote()) {
            ssrfGuard.validate(config.url().toString());
        }
        return new ManagedClient(client);
    }

    private SsrfGuard requireSsrfGuard(McpServerConfig config) {
        if (ssrfGuard == null) {
            throw new McpCallException("MCP " + config.transport().wireName()
                    + " 服务端需要出网白名单校验（SsrfGuard 未装配，拒绝连接）");
        }
        return ssrfGuard;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public List<McpToolDescriptor> tools() {
        return List.copyOf(descriptors);
    }

    public Map<String, String> failures() {
        return Map.copyOf(failures);
    }

    /** 装配代数：每成功 reload 一次 +1。用于排障与「这次运行看到的是第几代清单」。 */
    public long generation() {
        return generation.get();
    }

    /** 某个服务端当前的版本号（未装配时为 null）。 */
    public String version(String serverId) {
        ManagedClient managed = clients.get(serverId);
        return managed == null ? null : managed.version;
    }

    /**
     * 运行期一致性校验：注册表里那条快照，和「现在」的线上快照还是不是同一条。
     *
     * <p>三种不一致都**明确失败**而不是硬着头皮调用：工具消失、服务端整体不可用、定义变了。
     * 这三种情况下「按旧定义调用新工具」都会让结果不可信，那比直接失败更糟 ——
     * 失败至少能让编排者重跑一次，拿到的是新定义下的正确结果。
     */
    public void verifyTool(McpToolDescriptor pinned) {
        if (pinned == null) {
            return;
        }
        McpToolDescriptor current = descriptors.stream()
                .filter(item -> item.serverId().equals(pinned.serverId()) && item.name().equals(pinned.name()))
                .findFirst().orElse(null);
        if (current == null) {
            String reason = failures.get(pinned.serverId());
            throw new McpCallException("MCP 工具 " + pinned.serverId() + "." + pinned.name()
                    + " 在当前快照里已不存在（运行开始时是 " + pinned.fingerprint() + "）："
                    + (reason == null ? "服务端删除了它，或本次热更新把它移出了白名单" : "服务端不可用（" + reason + "）")
                    + "；本次运行拒绝按旧定义调用，请重跑");
        }
        if (!current.fingerprint().equals(pinned.fingerprint())) {
            throw new McpCallException("MCP 工具 " + pinned.serverId() + "." + pinned.name()
                    + " 的定义在本次运行期间被热更新改变（" + pinned.fingerprint() + " -> " + current.fingerprint()
                    + "）：拒绝按旧定义调用，请重跑该运行以使用新定义");
        }
    }

    /** 服务端快照：看清楚「这个工具是本地进程给的还是远程给的、凭证走的哪条路」。**不含任何凭证**。 */
    public List<Map<String, Object>> serverSnapshots() {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (ManagedClient managed : clients.values()) {
            McpServerConfig config = managed.client.config();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", config.id());
            item.put("transport", config.transport().wireName());
            item.put("source", config.source());
            item.put("url", safeEndpoint(config.url()));
            item.put("tools", managed.tools.stream().map(McpToolDescriptor::name).toList());
            item.put("version", managed.version);
            item.put("generation", generation.get());
            item.put("loadedAt", managed.loadedAt.toString());
            item.put("auth", managed.client.describeAuth());
            item.put("inFlight", managed.inFlight.get());
            snapshots.add(item);
        }
        return snapshots;
    }

    /**
     * 端点只在诊断输出中出现安全摘要：协议、host、显式端口和路径。
     * userinfo、query、fragment 都可能承载凭证或一次性令牌，不能进入快照/API。
     */
    static String safeEndpoint(URI endpoint) {
        return ToolUrlSummary.describe(endpoint);
    }

    static String safeEndpoint(String rawEndpoint) {
        if (rawEndpoint == null || rawEndpoint.isBlank()) {
            return "远程端点（地址无效）";
        }
        try {
            return safeEndpoint(URI.create(rawEndpoint.strip()));
        } catch (IllegalArgumentException ignored) {
            return "远程端点（地址无效）";
        }
    }

    /** 兼容旧异常消息：仅在进入 failures/log 之前做一次 URL 级别的兜底脱敏。 */
    static String safeFailure(String message, URI configuredEndpoint) {
        return safeFailure(message, configuredEndpoint == null ? null : configuredEndpoint.toString());
    }

    static String safeFailure(String message, String configuredEndpoint) {
        String safe = message == null || message.isBlank() ? "未知错误" : message;
        if (configuredEndpoint != null && !configuredEndpoint.isBlank()) {
            safe = safe.replace(configuredEndpoint.strip(), safeEndpoint(configuredEndpoint));
        }
        Matcher matcher = URI_TOKEN.matcher(safe);
        StringBuffer redacted = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(redacted, Matcher.quoteReplacement(safeEndpoint(matcher.group())));
        }
        matcher.appendTail(redacted);
        return redacted.toString();
    }

    /** 引擎内的工具 id：默认 {@code mcp.<serverId>.<toolName>}。 */
    public String toolId(McpToolDescriptor descriptor) {
        return properties.idPrefix() + descriptor.serverId() + "." + descriptor.name();
    }

    public McpToolResult call(String serverId, String toolName, Map<String, Object> arguments) {
        ManagedClient managed = clients.get(serverId);
        if (managed == null) {
            throw new McpCallException("MCP 服务端 " + serverId + " 未装配"
                    + (failures.containsKey(serverId) ? "（原因：" + failures.get(serverId) + "）" : ""));
        }
        ObjectNode args = mapper.createObjectNode();
        if (arguments != null) {
            arguments.forEach((key, value) -> args.set(key, mapper.valueToTree(value)));
        }
        managed.inFlight.incrementAndGet();
        try {
            JsonNode result = managed.client.callTool(toolName, args);
            return normalize(result);
        } finally {
            managed.inFlight.decrementAndGet();
        }
    }

    static McpToolResult normalize(JsonNode result) {
        boolean error = result.path("isError").asBoolean(false);
        JsonNode content = result.path("content");
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode item : content) {
                if (!"text".equals(item.path("type").asText("text"))) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.append("\n");
                }
                text.append(item.path("text").asText(""));
            }
        }
        if (text.isEmpty()) {
            text.append(content.isMissingNode() ? result.toString() : content.toString());
        }
        return new McpToolResult(text.toString(), error, result);
    }

    /** 等旧客户端上的在途调用结束再关；等不到就强关并留 WARN（一次热更新不该被卡死的调用无限拖住）。 */
    private void closeDrained(Iterable<ManagedClient> previous) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, properties.drainTimeoutMs()));
        for (ManagedClient managed : previous) {
            while (managed.inFlight.get() > 0 && System.nanoTime() < deadline) {
                LockSupport.parkNanos(DRAIN_POLL_NANOS);
            }
            int remaining = managed.inFlight.get();
            if (remaining > 0) {
                // 超时了就不再等，但**不能**在这里同步 close：客户端内部的锁被在途请求持有，
                // 同步关会一直等到那次调用结束（最长到 call-timeout），把 reload 也一起拖住。
                // 交给一条虚拟线程去关：调用跑完自然让出锁，关闭随即完成。
                log.warn("MCP 服务端 {} 在 drain 超时（{} ms）时仍有 {} 个在途调用：改为异步关闭，本次热更新不等它",
                        managed.client.serverId(), properties.drainTimeoutMs(), remaining);
                Thread.ofVirtual().name("mcp-close-" + managed.client.serverId()).start(managed.client::close);
                continue;
            }
            managed.client.close();
        }
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        closeDrained(clients.values());
        clients.clear();
    }

    /** 一个已装配服务端的运行态：客户端 + 在途计数 + 版本（drain 按**实例**计数，不按 serverId）。 */
    private static final class ManagedClient {

        private final McpClient client;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final Instant loadedAt = Instant.now();
        private volatile String version = "";
        private volatile List<McpToolDescriptor> tools = List.of();

        private ManagedClient(McpClient client) {
            this.client = client;
        }
    }
}
