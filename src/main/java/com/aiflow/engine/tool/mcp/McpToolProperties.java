package com.aiflow.engine.tool.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具配置：服务端声明 + 从本机已有 Agent 配置里导入。
 *
 * <p>与 HTTP 工具共用 {@code aiflow.tools.enabled} 这道总闸：总闸关着时任何工具都进不了注册表，
 * 但 MCP 另有 {@code aiflow.tools.mcp.enabled} 这道来源闸（默认关）—— 它会**拉起本地进程或带着凭证对外发请求**，
 * 与「读一个本地文件」不是同一类动作，默认必须更严。
 *
 * <p>便捷接入的三条路：
 * <ol>
 *   <li>引擎自己的 yml：{@code aiflow.tools.mcp.servers} 直接声明</li>
 *   <li>复用本机已有配置：{@code aiflow.tools.mcp.imports} 指向 Codex 的 {@code config.toml} 或
 *       Claude 的 {@code .mcp.json} / {@code .claude.json}，启动时把里面的 mcpServers 抽出来</li>
 *   <li>本地技能库（{@code aiflow.tools.skills}）—— 那是另一套来源，不在这里配</li>
 * </ol>
 *
 * <p>三种传输形态（{@code stdio} / {@code http} / {@code sse}）由 {@code transport} 显式声明（默认 {@code auto}）；
 * 远程形态额外要过 {@code aiflow.tools.allow-hosts} 这套出网白名单。
 *
 * @param enabled         来源闸（默认 false）
 * @param idPrefix        注册进引擎的工具 id 前缀，默认 {@code mcp.}，配 {@code ""} 可去掉
 * @param startupTimeoutMs 默认建流 / 握手超时
 * @param callTimeoutMs   默认调用超时
 * @param drainTimeoutMs  热更新时等待在途调用结束的上限（超过就强关连接并留 WARN）
 * @param servers         服务端声明
 * @param imports         要导入的外部配置文件
 */
@ConfigurationProperties(prefix = "aiflow.tools.mcp")
public record McpToolProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("mcp.") String idPrefix,
        @DefaultValue("20000") long startupTimeoutMs,
        @DefaultValue("30000") long callTimeoutMs,
        @DefaultValue("5000") long drainTimeoutMs,
        List<Server> servers,
        List<ImportSpec> imports
) {

    @ConstructorBinding
    public McpToolProperties {
        servers = servers == null ? List.of() : List.copyOf(servers);
        imports = imports == null ? List.of() : List.copyOf(imports);
        idPrefix = idPrefix == null ? "mcp." : idPrefix;
    }

    /**
     * 兼容旧调用点（不含 drain 超时的四参数构造）。
     *
     * <p>record 一旦有第二个构造器，Spring 就无法自己挑出绑定的那个 —— 必须由 {@link ConstructorBinding}
     * 指定规范构造器，否则启动时报「No default constructor found」。
     */
    public McpToolProperties(boolean enabled, String idPrefix, long startupTimeoutMs, long callTimeoutMs,
                             List<Server> servers, List<ImportSpec> imports) {
        this(enabled, idPrefix, startupTimeoutMs, callTimeoutMs, 5_000, servers, imports);
    }

    /**
     * @param id               服务端 id
     * @param enabled          单个服务端开关
     * @param command          可执行文件（stdio）
     * @param args             启动参数（stdio）
     * @param env              环境变量（stdio）
     * @param passThroughEnv   按名透传父进程环境变量（stdio）
     * @param workDir          工作目录（stdio）
     * @param url              端点地址（远程形态）
     * @param headers          请求头（远程形态）；值支持 ${VAR} / ${VAR:-default} 占位符
     * @param authTokenEnv     静态 Bearer token 的环境变量名（远程形态）
     * @param startupTimeoutMs 覆盖默认握手超时（空 = 用默认值）
     * @param callTimeoutMs    覆盖默认调用超时（空 = 用默认值）
     * @param allowedTools     工具白名单（空 = 全放行）
     * @param transport        传输形态：auto（默认，按 command/url 推断）/ stdio / http / sse
     * @param oauth            动态令牌配置（与 authTokenEnv 二选一）
     */
    public record Server(
            String id,
            @DefaultValue("true") boolean enabled,
            String command,
            List<String> args,
            Map<String, String> env,
            List<String> passThroughEnv,
            String workDir,
            String url,
            Map<String, String> headers,
            String authTokenEnv,
            Long startupTimeoutMs,
            Long callTimeoutMs,
            List<String> allowedTools,
            String transport,
            OAuth oauth
    ) {
        @ConstructorBinding
        public Server {
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
            passThroughEnv = passThroughEnv == null ? List.of() : List.copyOf(passThroughEnv);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        }

        /** 兼容旧调用点（不含 transport / oauth 的十三参数构造）。 */
        public Server(String id, boolean enabled, String command, List<String> args, Map<String, String> env,
                      List<String> passThroughEnv, String workDir, String url, Map<String, String> headers,
                      String authTokenEnv, Long startupTimeoutMs, Long callTimeoutMs,
                      List<String> allowedTools) {
            this(id, enabled, command, args, env, passThroughEnv, workDir, url, headers, authTokenEnv,
                    startupTimeoutMs, callTimeoutMs, allowedTools, null, null);
        }
    }

    /**
     * OAuth 令牌配置（远程形态）。密钥同样只写变量名，值从环境读。
     *
     * @param tokenUrl             令牌端点
     * @param clientIdEnv          client_id 的环境变量名
     * @param clientSecretEnv      client_secret 的环境变量名（公共客户端可空）
     * @param refreshTokenEnv      refresh_token 的环境变量名（配了就走 refresh_token 授权）
     * @param scope                申请的 scope
     * @param extraParams          额外表单参数（audience / resource 等）
     * @param earlyRefreshSeconds  到期前提前刷新的秒数（默认 60）
     */
    public record OAuth(String tokenUrl, String clientIdEnv, String clientSecretEnv, String refreshTokenEnv,
                        String scope, Map<String, String> extraParams, Long earlyRefreshSeconds) {

        public OAuth {
            extraParams = extraParams == null ? Map.of() : Map.copyOf(extraParams);
        }
    }

    /**
     * @param path     配置文件路径
     * @param format   {@code auto}（按扩展名猜）/ {@code codex-toml} / {@code claude-json}
     * @param idPrefix 给导入进来的服务端 id 加前缀（避免与本机多个 Agent 的同名服务端撞名）
     */
    public record ImportSpec(String path, @DefaultValue("auto") String format, @DefaultValue("") String idPrefix) {
    }
}
