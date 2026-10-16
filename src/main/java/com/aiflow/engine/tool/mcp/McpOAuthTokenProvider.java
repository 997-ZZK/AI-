package com.aiflow.engine.tool.mcp;

import com.aiflow.engine.node.RunCanceledException;
import com.aiflow.engine.tool.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OAuth 令牌客户端：拿一次、缓存、到期前刷新，**同一时刻只允许一次刷新**。
 *
 * <p>为什么引擎要做这件事：静态环境变量令牌的问题不是「不安全」，而是「会过期」——
 * 运维要么每小时手工换一次，要么把令牌的有效期设成永不失效（那才是真不安全）。
 * 让引擎按 OAuth 流程自己换令牌，静态令牌退回「测试与小服务」的位置。
 *
 * <p>四个实现决定：
 * <ul>
 *   <li><b>提前刷新</b>（默认 60s）：踩点刷新等于「每 N 次调用里有几次会撞上过期窗口」，
 *       而失败重试的代价远高于提前 60 秒换一次令牌</li>
 *   <li><b>单飞（single-flight）</b>：刷新加锁 + 锁内二次检查，并发调用只产生一次令牌请求 ——
 *       令牌端点常有限流，把「并发」直接翻译成「被限流」是自找的</li>
 *   <li><b>密钥不落日志</b>：日志只记「刷新成功 / 剩余有效期」，令牌与 secret 一律不进日志、不进异常消息</li>
 *   <li><b>令牌端点也过出网白名单</b>：换令牌也是一次出网请求，凭什么它就能绕过 {@link SsrfGuard}</li>
 * </ul>
 *
 * <p>不做的事情：不做本地持久化（重启后重新换一次令牌的成本可以接受，落盘等于多一份要保护的密钥）。
 */
public final class McpOAuthTokenProvider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthTokenProvider.class);
    private static final int ERROR_BODY_LIMIT = 300;
    private static final int DETAIL_LIMIT = 200;
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 300;

    private final String serverId;
    private final McpServerConfig.OAuth config;
    private final Function<String, String> envLookup;
    private final HttpClient httpClient;
    private final SsrfGuard ssrfGuard;
    private final ObjectMapper mapper;
    private final AtomicInteger refreshCount = new AtomicInteger();
    private final Object lock = new Object();

    private volatile String accessToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public McpOAuthTokenProvider(String serverId, McpServerConfig.OAuth config, Function<String, String> envLookup,
                                 HttpClient httpClient, SsrfGuard ssrfGuard, ObjectMapper mapper) {
        this.serverId = serverId;
        this.config = config;
        this.envLookup = envLookup;
        this.httpClient = httpClient;
        this.ssrfGuard = ssrfGuard;
        this.mapper = mapper;
    }

    /** 取一个可用令牌：有效就直接给，快过期就刷新（并发下只刷一次）。 */
    public String accessToken() {
        if (valid()) {
            return accessToken;
        }
        synchronized (lock) {
            if (valid()) {
                return accessToken;
            }
            refresh();
            return accessToken;
        }
    }

    private boolean valid() {
        String token = accessToken;
        return token != null && Instant.now().isBefore(expiresAt.minusSeconds(config.effectiveEarlyRefreshSeconds()));
    }

    private void refresh() {
        String clientId = requireEnv(config.clientIdEnv(), "client_id");
        String clientSecret = blankToNull(envLookup.apply(config.clientSecretEnv()));
        String refreshToken = config.refreshTokenEnv() == null || config.refreshTokenEnv().isBlank()
                ? null : requireEnv(config.refreshTokenEnv(), "refresh_token");

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", config.grantType());
        form.put("client_id", clientId);
        if (clientSecret != null) {
            form.put("client_secret", clientSecret);
        }
        if (refreshToken != null) {
            form.put("refresh_token", refreshToken);
        }
        if (config.scope() != null && !config.scope().isBlank()) {
            form.put("scope", config.scope().strip());
        }
        EnvPlaceholders.resolveAll(config.extraParams(), envLookup).forEach(form::putIfAbsent);

        URI uri = URI.create(config.tokenUrl().strip());
        if (ssrfGuard != null) {
            uri = ssrfGuard.validate(uri.toString());
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new McpCallException("MCP 服务端 " + serverId + " 的 OAuth 令牌请求不合法（不回显参数值）: "
                    + e.getMessage());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new McpCallException("MCP 服务端 " + serverId + " 的 OAuth 令牌端点超时（15s）："
                    + config.tokenUrl());
        } catch (IOException e) {
            throw new McpCallException("MCP 服务端 " + serverId + " 的 OAuth 令牌端点请求失败（网络层）: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        }

        int status = response.statusCode();
        if (status >= 400) {
            String hint = status == 401 || status == 403
                    ? "（检查 " + config.clientIdEnv() + " / " + config.clientSecretEnv()
                            + " 两个环境变量，以及该客户端有没有被授权这个 scope）"
                    : "";
            throw new McpCallException("MCP 服务端 " + serverId + " 的 OAuth 令牌端点返回 HTTP " + status + hint
                    + "：" + abbreviate(response.body()));
        }

        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new McpCallException("MCP 服务端 " + serverId + " 的 OAuth 令牌响应不是合法 JSON: "
                    + abbreviate(response.body()));
        }
        String token = body.path("access_token").asText("");
        if (token.isBlank()) {
            throw new McpCallException("MCP 服务端 " + serverId + " 的 OAuth 令牌响应缺少 access_token"
                    + "（字段名必须是 RFC 6749 的 access_token）");
        }
        long expiresIn = body.path("expires_in").isNumber()
                ? body.path("expires_in").asLong(DEFAULT_EXPIRES_IN_SECONDS)
                : parseSeconds(body.path("expires_in").asText(""));
        this.accessToken = token;
        this.expiresAt = Instant.now().plusSeconds(expiresIn);
        refreshCount.incrementAndGet();
        log.info("MCP 服务端 {} 的 OAuth 令牌已{}（grant={}，{} 秒后过期，提前 {} 秒刷新）",
                serverId, refreshCount.get() == 1 ? "获取" : "刷新", config.grantType(), expiresIn,
                config.effectiveEarlyRefreshSeconds());
    }

    private static long parseSeconds(String value) {
        try {
            return value.isBlank() ? DEFAULT_EXPIRES_IN_SECONDS : Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return DEFAULT_EXPIRES_IN_SECONDS;
        }
    }

    private String requireEnv(String name, String what) {
        String value = name == null || name.isBlank() ? null : envLookup.apply(name.strip());
        if (value == null || value.isBlank()) {
            throw new McpCallException("MCP 服务端 " + serverId + " 的 OAuth " + what + " 环境变量 "
                    + (name == null ? "(未配置)" : name) + " 未设置（配置里只写变量名，值必须来自环境）");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.lines().map(String::strip).filter(part -> !part.isEmpty())
                .collect(Collectors.joining(" "));
        return flat.length() <= DETAIL_LIMIT ? flat : flat.substring(0, DETAIL_LIMIT) + "…";
    }

    /** 诊断用：说清楚「用的是哪套凭证、还有多久过期」，但**不含任何令牌内容**。 */
    public String describe() {
        if (accessToken == null) {
            return "oauth(" + config.grantType() + ", 未获取)";
        }
        long remaining = Math.max(0, Duration.between(Instant.now(), expiresAt).toSeconds());
        return "oauth(" + config.grantType() + ", " + remaining + "s 后过期)";
    }

    /** 测试可见：刷新次数。生产代码不用它做判断。 */
    int refreshCount() {
        return refreshCount.get();
    }

    @Override
    public void close() {
        accessToken = null;
        expiresAt = Instant.EPOCH;
    }
}
