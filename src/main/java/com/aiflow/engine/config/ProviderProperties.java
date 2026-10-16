package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * provider 路由与端点配置。
 *
 * <p>{@code order} 就是分级顺序：靠前的档位先被选中，调用失败再往下一档退。
 * 默认 {@code local -> cloud -> mock}：本地模型兜底（离线可用），云端用于加速与复杂任务，
 * 最后剩下的 mock 只在「连本地模型都没有」时兜住流程，避免整条链路因为模型缺失直接 500。
 * 把 mock 从 order 里摘掉，就得到「宁可不答也不假装答」的严格模式。
 *
 * <p>{@code cheapTiers} 是「便宜档位」的档位名集合：用户接近自己的额度上限时（见 {@code aiflow.budget}），
 * 路由会把这些档位提到前面 —— 降级而不是中断。默认 {@code local + mock}，按实际成本改。
 *
 * <p>{@code endpoints} 的 key 即 provider id，本地 Ollama 与云端 OpenAI 兼容网关吃同一份契约：
 * <pre>
 * aiflow.provider.endpoints.local.base-url = http://localhost:11434/v1
 * aiflow.provider.endpoints.local.model   = qwen2.5:1.5b
 * aiflow.provider.endpoints.cloud.base-url = https://&lt;gateway&gt;/v1
 * aiflow.provider.endpoints.cloud.capabilities = chat,vision
 * </pre>
 */
@ConfigurationProperties(prefix = "aiflow.provider")
public record ProviderProperties(
        @DefaultValue("auto") String mode,
        @DefaultValue({"local", "cloud", "mock"}) String[] order,
        @DefaultValue({"local", "mock"}) List<String> cheapTiers,
        @DefaultValue Mock mock,
        Map<String, Endpoint> endpoints,
        @DefaultValue Probe probe,
        @DefaultValue Breaker breaker
) {

    public ProviderProperties {
        // 保留声明顺序：同一个档位可以有多个端点（云端主备），谁写在前面先用谁。
        // Map.copyOf 的迭代顺序是不定的，会把「主备」变成「随机」。
        endpoints = endpoints == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(endpoints));
        cheapTiers = cheapTiers == null ? List.of() : List.copyOf(cheapTiers);
    }

    /**
     * 一个 HTTP 端点。本地与云端只有 base-url / model / api-key / 能力集不同，代码路径完全相同。
     *
     * <p>{@code tier} 是降级矩阵里的档位名（{@code local} / {@code cloud}），留空时按 base-url 推导
     * （本机地址算 local）。要显式声明是因为「本机还是远端」只是默认规则：内网自建的大模型集群地址不是
     * localhost，但它在语义上更接近本地档 —— 这种判断只有配置的人知道，不该由猜地址的代码决定。
     */
    public record Endpoint(
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String model,
            @DefaultValue("") String apiKey,
            @DefaultValue({"chat"}) Set<String> capabilities,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60000") long timeoutMs,
            @DefaultValue("") String tier,
            /**
             * 厂商私有的请求体字段（JSON 对象），最后合并进请求体：火山方舟的 thinking、DashScope 的
             * enable_thinking 都属于这一层。协议主干（model / messages / stream）不允许覆盖，启动期就会拒绝。
             */
            @DefaultValue("") String extraBody,
            /**
             * 探活超时（毫秒），0 = 继承 aiflow.provider.probe.timeout-ms。
             *
             * <p>为什么要按端点配：探活打的是 GET /models，而模型清单的大小是厂商决定的 —— 火山方舟的清单
             * 有几百 KB，1.5s 的全局超时会让它偶发超时，档位在 up/down 之间反复跳（2026-09-22 实测：
             * 一次视觉请求正好落在 down 窗口里，被当成「不可用」直接跳过、降级到 mock）。
             */
            @DefaultValue("0") long probeTimeoutMs
    ) {

        public Endpoint {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    /** 探活：定期 GET {base-url}/models，结果写回 EndpointHealth。关闭后熔断仍按真实调用失败计数。 */
    public record Probe(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("15000") long intervalMs,
            @DefaultValue("1500") long timeoutMs
    ) {
    }

    /** 熔断参数：连续失败 failureThreshold 次后打开 cooldownMs 毫秒。 */
    public record Breaker(
            @DefaultValue("3") int failureThreshold,
            @DefaultValue("30000") long cooldownMs
    ) {
    }

    /** mock 强制模式：只有显式列出 mock 且 mode=mock 时才只用 mock。 */
    public record Mock(
            @DefaultValue("0") long latencyMs,
            @DefaultValue("30") long chunkDelayMs,
            @DefaultValue("8") int chunkSize
    ) {
    }

    public static ProviderProperties defaults() {
        return new ProviderProperties("auto", new String[]{"local", "cloud", "mock"}, List.of("local", "mock"),
                new Mock(0, 30, 8), Map.of(), new Probe(true, 15_000, 1_500), new Breaker(3, 30_000));
    }
}