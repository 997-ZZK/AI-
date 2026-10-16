package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * 工具调用（HTTP 工具）配置。
 *
 * <p>默认全关：开了工具就等于允许引擎主动出网，这个动作必须是显式的选择，而不是默认行为。
 *
 * @param enabled           总开关
 * @param timeoutMs         单次调用超时
 * @param maxBodyBytes      读取响应体的上限（超出截断并标记，不把大响应灌进内存与快照）
 * @param maxCallsPerRun    一次运行的工具调用总次数上限
 * @param loopThreshold     同一（工具 + 入参）指纹的重复上限，超过判定为工具循环
 * @param allowHosts        host 白名单，支持 {@code *.example.com} 通配子域；不在名单里的 host 一律拒绝
 * @param allowPrivateHosts 允许解析到内网/回环地址的 host（默认空 = 内网地址一律拒绝，防 SSRF）
 * @param allowPorts        允许的端口（http/https 默认端口已在名单内）
 * @param registry          工具定义（key 即工具 id）
 */
@ConfigurationProperties(prefix = "aiflow.tools")
public record ToolProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5000") long timeoutMs,
        @DefaultValue("65536") int maxBodyBytes,
        @DefaultValue("8") int maxCallsPerRun,
        @DefaultValue("3") int loopThreshold,
        List<String> allowHosts,
        List<String> allowPrivateHosts,
        @DefaultValue({"80", "443"}) List<Integer> allowPorts,
        Map<String, Tool> registry
) {

    public ToolProperties {
        allowHosts = allowHosts == null ? List.of() : List.copyOf(allowHosts);
        allowPrivateHosts = allowPrivateHosts == null ? List.of() : List.copyOf(allowPrivateHosts);
        allowPorts = allowPorts == null ? List.of(80, 443) : List.copyOf(allowPorts);
        registry = registry == null ? Map.of() : Map.copyOf(registry);
    }

    /**
     * 一个工具定义。url 是固定模板：入参可以渲染进 query 或 body，但**host 由配置决定** ——
     * 真正的护栏在调用时对最终 URI 再做一次白名单与内网地址校验。
     *
     * @param idempotency 幂等策略：{@code content}（默认，按入参指纹去重）/ {@code none}（每次真调用）；
     *                    GET/HEAD 天然幂等，不需要缓存
     */
    public record Tool(
            @DefaultValue("") String description,
            @DefaultValue("GET") String method,
            @DefaultValue("") String url,
            Map<String, String> headers,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("content") String idempotency
    ) {

        public Tool {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public static ToolProperties disabled() {
        return new ToolProperties(false, 5_000, 65_536, 8, 3, List.of(), List.of(), List.of(80, 443), Map.of());
    }
}