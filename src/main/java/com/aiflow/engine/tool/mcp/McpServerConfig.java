package com.aiflow.engine.tool.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一个 MCP（Model Context Protocol）服务端的声明。
 *
 * <p>传输形态由 {@code transport} 显式声明（{@code auto} 时按「写了 command 还是 url」推断）；
 * 三种形态的权限面不同，所以校验也不同：stdio 只看「命令来自哪个配置文件」，
 * 远程形态（HTTP / SSE）还要过 {@code allow-hosts} / {@code allow-ports} 这套出网白名单。
 *
 * <p>凭证只以「环境变量名」出现：静态令牌是 {@code authTokenEnv}（或 headers 值里的 {@code ${VAR}}），
 * 动态令牌是 {@link OAuth}（client_id / client_secret / refresh_token 同样只写变量名）。
 * 两条路径互斥 —— 同时配会把「到底用了哪套凭证」变成排障时的猜谜。
 *
 * @param id            引擎内的服务端名（工具 id 前缀里用它）
 * @param transport     传输形态（auto 由工厂方法推断）
 * @param command       可执行文件（stdio 形态；必须由配置显式声明，节点不能绕开配置拼命令）
 * @param args          启动参数（stdio 形态）
 * @param env           追加到子进程环境变量（stdio 形态；父进程环境会被继承）
 * @param passThroughEnv 从父进程按名透传的环境变量（stdio 形态；不设时留空，避免把密钥传进子进程）
 * @param workDir       子进程工作目录，空 = 引擎进程的当前目录（stdio 形态）
 * @param url           MCP 端点地址（HTTP：单端点；SSE：GET 长连接地址）
 * @param headers       请求头；值支持 {@code ${VAR}} / {@code ${VAR:-default}} 占位符（远程形态）
 * @param authTokenEnv  读取静态 Bearer token 的环境变量名（远程形态；不落明文）
 * @param oauth         OAuth 动态令牌配置（远程形态；与 authTokenEnv 二选一）
 * @param startupTimeoutMs 握手 / 建流超时
 * @param callTimeoutMs 单次 tools/call 超时
 * @param allowedTools  工具白名单（空 = 该服务端所有工具都可用）
 * @param source        来源描述（配置 / 从哪个文件导入的），只用于日志与诊断
 */
public record McpServerConfig(
        String id,
        McpTransport transport,
        String command,
        List<String> args,
        Map<String, String> env,
        List<String> passThroughEnv,
        Path workDir,
        URI url,
        Map<String, String> headers,
        String authTokenEnv,
        OAuth oauth,
        long startupTimeoutMs,
        long callTimeoutMs,
        List<String> allowedTools,
        String source
) {

    public McpServerConfig {
        transport = transport == null ? (url == null ? McpTransport.STDIO : McpTransport.HTTP) : transport;
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        passThroughEnv = passThroughEnv == null ? List.of() : List.copyOf(passThroughEnv);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        source = source == null ? "config" : source;
        if (transport == McpTransport.STDIO) {
            if (command == null || command.isBlank()) {
                throw new McpCallException("MCP 服务端 " + id + " 是 stdio 形态但没有 command");
            }
            if (url != null) {
                throw new McpCallException("MCP 服务端 " + id + " 是 stdio 形态，不应同时声明 url");
            }
        } else {
            if (url == null) {
                throw new McpCallException("MCP " + transport.wireName() + " 服务端 " + id + " 缺少 url");
            }
            if (command != null && !command.isBlank()) {
                throw new McpCallException("MCP 服务端 " + id + " 同时声明了 command 与 url，无法判断传输形态（用 transport 明确指定）");
            }
            if (oauth != null && authTokenEnv != null && !authTokenEnv.isBlank()) {
                throw new McpCallException("MCP 服务端 " + id + " 同时声明了 oauth 与 authTokenEnv："
                        + "二选一（前者是动态令牌，后者是静态令牌）");
            }
        }
    }

    /** stdio 形态（本地子进程）。 */
    public static McpServerConfig stdio(String id, String command, List<String> args, Map<String, String> env,
                                        List<String> passThroughEnv, Path workDir, long startupTimeoutMs,
                                        long callTimeoutMs, List<String> allowedTools, String source) {
        return new McpServerConfig(id, McpTransport.STDIO, command, args, env, passThroughEnv, workDir, null,
                Map.of(), null, null, startupTimeoutMs, callTimeoutMs, allowedTools, source);
    }

    /** HTTP 形态（Streamable HTTP 单端点）。 */
    public static McpServerConfig http(String id, String url, Map<String, String> headers, String authTokenEnv,
                                       long startupTimeoutMs, long callTimeoutMs, List<String> allowedTools,
                                       String source) {
        return http(id, url, headers, authTokenEnv, null, startupTimeoutMs, callTimeoutMs, allowedTools, source);
    }

    /** HTTP 形态 + OAuth 动态令牌。 */
    public static McpServerConfig http(String id, String url, Map<String, String> headers, String authTokenEnv,
                                       OAuth oauth, long startupTimeoutMs, long callTimeoutMs,
                                       List<String> allowedTools, String source) {
        return new McpServerConfig(id, McpTransport.HTTP, null, List.of(), Map.of(), List.of(), null,
                endpoint(id, url, "HTTP"), headers, authTokenEnv, oauth, startupTimeoutMs, callTimeoutMs,
                allowedTools, source);
    }

    /** 老式 SSE 双端点形态。 */
    public static McpServerConfig sse(String id, String url, Map<String, String> headers, String authTokenEnv,
                                      long startupTimeoutMs, long callTimeoutMs, List<String> allowedTools,
                                      String source) {
        return sse(id, url, headers, authTokenEnv, null, startupTimeoutMs, callTimeoutMs, allowedTools, source);
    }

    /** 老式 SSE 双端点形态 + OAuth 动态令牌。 */
    public static McpServerConfig sse(String id, String url, Map<String, String> headers, String authTokenEnv,
                                      OAuth oauth, long startupTimeoutMs, long callTimeoutMs,
                                      List<String> allowedTools, String source) {
        return new McpServerConfig(id, McpTransport.SSE, null, List.of(), Map.of(), List.of(), null,
                endpoint(id, url, "SSE"), headers, authTokenEnv, oauth, startupTimeoutMs, callTimeoutMs,
                allowedTools, source);
    }

    /** 协议与 host 在这里就检查，避免一个错 url 拖到第一次调用才失败。 */
    private static URI endpoint(String id, String url, String form) {
        if (url == null || url.isBlank()) {
            throw new McpCallException("MCP " + form + " 服务端 " + id + " 缺少 url");
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            // 原始地址可能含 userinfo/query/fragment 形式的凭证；配置错误只保留固定分类。
            throw new McpCallException("MCP " + form + " 服务端 " + id + " 的 url 无法解析");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new McpCallException("MCP " + form + " 服务端 " + id + " 只支持 http/https，拒绝协议: "
                    + (scheme.isEmpty() ? "(空)" : scheme));
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new McpCallException("MCP " + form + " 服务端 " + id + " 的 url 缺少 host");
        }
        return uri;
    }

    public boolean isStdio() {
        return transport == McpTransport.STDIO;
    }

    public boolean isHttp() {
        return transport == McpTransport.HTTP;
    }

    public boolean isSse() {
        return transport == McpTransport.SSE;
    }

    public boolean allows(String toolName) {
        return allowedTools.isEmpty() || allowedTools.contains(toolName);
    }

    /**
     * OAuth 令牌配置：引擎自己换令牌，而不是让运维每小时手工刷新一次环境变量。
     *
     * <p>授权方式由「有没有 refresh_token」推出：有则 {@code refresh_token}，
     * 没有则 {@code client_credentials}。不引入 more 抽象 —— 这两种覆盖了服务端到服务端的绝大多数场景。
     *
     * @param tokenUrl        令牌端点（同样要过出网白名单）
     * @param clientIdEnv     client_id 的环境变量名
     * @param clientSecretEnv client_secret 的环境变量名（公共客户端可空）
     * @param refreshTokenEnv refresh_token 的环境变量名（可空；配了它就走 refresh_token 授权）
     * @param scope           申请的 scope（可空）
     * @param extraParams     额外表单参数（如 audience / resource），值同样支持 ${VAR} 占位符
     * @param earlyRefreshSeconds 到期前多少秒提前刷新（默认 60）
     */
    public record OAuth(String tokenUrl, String clientIdEnv, String clientSecretEnv, String refreshTokenEnv,
                        String scope, Map<String, String> extraParams, Long earlyRefreshSeconds) {

        public OAuth {
            extraParams = extraParams == null ? Map.of() : Map.copyOf(extraParams);
            if (tokenUrl == null || tokenUrl.isBlank()) {
                throw new McpCallException("MCP 的 oauth 配置缺少 token-url");
            }
            if (clientIdEnv == null || clientIdEnv.isBlank()) {
                throw new McpCallException("MCP 的 oauth 配置缺少 client-id-env（密钥只从环境变量来，配置里只写变量名）");
            }
        }

        public String grantType() {
            return refreshTokenEnv == null || refreshTokenEnv.isBlank() ? "client_credentials" : "refresh_token";
        }

        /** 生效的提前刷新秒数（默认 60；组件本身可为空，所以另起一个名字避免与访问器冲突）。 */
        public long effectiveEarlyRefreshSeconds() {
            return earlyRefreshSeconds == null || earlyRefreshSeconds < 0 ? 60 : earlyRefreshSeconds;
        }
    }
}
