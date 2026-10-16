package com.aiflow.engine.tool.mcp;

import com.aiflow.engine.tool.SsrfGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * MCP 请求的凭证装配：静态（headers / authTokenEnv）与动态（OAuth）两条路，二者互斥。
 *
 * <p>抽出来是因为 HTTP 与 SSE 两种远程形态**要的是同一件事**：把「配置里只写变量名、值只从环境来」
 * 这条纪律落在一处。两份实现各写一遍，早晚会出现「一边支持 ${VAR} 占位符、另一边不支持」这种不一致。
 *
 * <p>静态令牌在构造时就校验（缺环境变量属于配置错误，装配期暴露比运行期好）；
 * 动态令牌是懒加载的（第一次请求时才换，换不到就带着明确原因让这次调用失败，而不是拖垮启动）。
 */
final class McpAuth implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpAuth.class);

    private final Map<String, String> staticHeaders;
    private final McpOAuthTokenProvider oauth;

    private McpAuth(Map<String, String> staticHeaders, McpOAuthTokenProvider oauth) {
        this.staticHeaders = staticHeaders;
        this.oauth = oauth;
    }

    static McpAuth resolve(McpServerConfig config, Function<String, String> envLookup, HttpClient httpClient,
                           SsrfGuard ssrfGuard, ObjectMapper mapper) {
        Map<String, String> resolved = new LinkedHashMap<>(EnvPlaceholders.resolveAll(config.headers(), envLookup));
        boolean headerAuth = resolved.containsKey("Authorization");
        if (config.oauth() != null) {
            if (headerAuth || (config.authTokenEnv() != null && !config.authTokenEnv().isBlank())) {
                log.warn("MCP 服务端 {} 同时配了静态 Authorization 与 oauth，以 oauth 动态令牌为准", config.id());
            }
            resolved.remove("Authorization");
            McpOAuthTokenProvider provider = new McpOAuthTokenProvider(config.id(), config.oauth(), envLookup,
                    httpClient, ssrfGuard, mapper);
            return new McpAuth(Map.copyOf(resolved), provider);
        }
        if (config.authTokenEnv() != null && !config.authTokenEnv().isBlank()) {
            String name = config.authTokenEnv().strip();
            String token = envLookup.apply(name);
            if (token == null || token.isBlank()) {
                throw new McpCallException("MCP 服务端 " + config.id() + " 的环境变量 " + name
                        + " 未设置（配置里只写变量名，值必须来自环境）");
            }
            if (headerAuth) {
                log.warn("MCP 服务端 {} 同时配了 Authorization 头与 authTokenEnv，以 authTokenEnv 为准", config.id());
            }
            resolved.put("Authorization", "Bearer " + token);
        }
        return new McpAuth(Map.copyOf(resolved), null);
    }

    /** 每条出站请求都要过一次：动态令牌会变，静态头每次照抄。 */
    void apply(HttpRequest.Builder builder) {
        staticHeaders.forEach(builder::header);
        if (oauth != null) {
            builder.header("Authorization", "Bearer " + oauth.accessToken());
        }
    }

    /** 诊断用（写进 /api/v1/tools 的快照）：说清楚凭证从哪来，但不含任何密钥。 */
    String describe() {
        if (oauth != null) {
            return oauth.describe();
        }
        return staticHeaders.containsKey("Authorization") ? "static-bearer(环境变量)" : "none";
    }

    @Override
    public void close() {
        if (oauth != null) {
            oauth.close();
        }
    }
}
