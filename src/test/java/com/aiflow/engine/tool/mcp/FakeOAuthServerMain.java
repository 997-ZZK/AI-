package com.aiflow.engine.tool.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的最小 OAuth 令牌端点（RFC 6749 的 token endpoint）。
 *
 * <p>它实现两种授权：{@code client_credentials}（没有 refresh_token）与 {@code refresh_token}，
 * 并且**每次下发的令牌都不一样**（token-1 / token-2 …）—— 这样测试才能证明「刷新过」而不是「一直在用同一个」。</
 *
 * <p>被拒绝的请求会计数（{@link #rejectedCount()}），用于断言「密钥错误时确实没有拿到令牌」。
 * 夹具只认表单体，不看 Authorization 头：真实世界两种都有，但引擎用的是表单体（RFC 6749 §2.3.1 的标准做法）。
 */
public final class FakeOAuthServerMain implements AutoCloseable {

    private final HttpServer server;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private final long expiresIn;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<String> grants = Collections.synchronizedList(new ArrayList<>());
    private volatile String currentToken = "";
    private volatile long delayMillis;

    public FakeOAuthServerMain(int port, String clientId, String clientSecret, String refreshToken, long expiresIn)
            throws IOException {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/token", this::handle);
        this.server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-oauth");
            thread.setDaemon(true);
            return thread;
        }));
        this.server.start();
    }

    /** 客户端凭证模式：只校验 client_id / client_secret。 */
    public static FakeOAuthServerMain startClientCredentials(String clientId, String clientSecret, long expiresIn)
            throws IOException {
        return new FakeOAuthServerMain(0, clientId, clientSecret, null, expiresIn);
    }

    /** 刷新令牌模式：还要求 refresh_token 对得上。 */
    public static FakeOAuthServerMain startRefreshToken(String clientId, String clientSecret, String refreshToken,
                                                          long expiresIn) throws IOException {
        return new FakeOAuthServerMain(0, clientId, clientSecret, refreshToken, expiresIn);
    }

    public String tokenUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    public int tokenRequestCount() {
        return tokenRequests.get();
    }

    public int rejectedCount() {
        return rejected.get();
    }

    /** 最近一次下发的令牌（假服务端用它可以校验「客户端带的是哪一版」）。 */
    public String currentToken() {
        return currentToken;
    }

    public List<String> grants() {
        return List.copyOf(grants);
    }

    public List<String> errors() {
        return List.copyOf(errors);
    }
    /** 令牌端点响应延迟：单飞（single-flight）用例需要「刷新慢到能让并发排队」。 */
    public void setResponseDelayMillis(long millis) {
        this.delayMillis = millis;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "expect POST".getBytes(StandardCharsets.UTF_8));
                return;
            }
            tokenRequests.incrementAndGet();
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(raw);
            String grant = form.getOrDefault("grant_type", "");
            grants.add(grant);
            boolean clientOk = clientId.equals(form.get("client_id"))
                    && (clientSecret == null || clientSecret.equals(form.get("client_secret")));
            boolean grantOk = refreshToken == null
                    ? "client_credentials".equals(grant)
                    : "refresh_token".equals(grant) && refreshToken.equals(form.get("refresh_token"));
            if (!clientOk || !grantOk) {
                rejected.incrementAndGet();
                send(exchange, 401, "{\"error\":\"invalid_client\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            currentToken = "fake-access-token-" + tokenRequests.get();
            String body = "{\"access_token\":\"" + currentToken + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                    + expiresIn + "}";
            send(exchange, 200, body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            errors.add(e.toString());
            try {
                send(exchange, 500, e.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 连接已经断了
            }
        }
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return form;
    }

    private void send(HttpExchange exchange, int status, byte[] payload) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * 手动联调用：{@code java -cp target/test-classes com.aiflow.engine.tool.mcp.FakeOAuthServerMain [port] [clientId] [clientSecret] [expiresIn]}。
     * 客户端凭证模式，端口默认 18792。首个下发的令牌是 {@code fake-access-token-1}（夹具每刷一次都会变）。
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18792;
        String clientId = args.length > 1 ? args[1] : "aiflow-e2e";
        String clientSecret = args.length > 2 ? args[2] : "e2e-secret";
        long expiresIn = args.length > 3 ? Long.parseLong(args[3]) : 3600;
        FakeOAuthServerMain server = new FakeOAuthServerMain(port, clientId, clientSecret, null, expiresIn);
        System.out.println("fake-oauth listening on " + server.tokenUrl()
                + "（grant=client_credentials，client_id=" + clientId + "，首个令牌 fake-access-token-1）");
        Thread.currentThread().join();
    }
}
