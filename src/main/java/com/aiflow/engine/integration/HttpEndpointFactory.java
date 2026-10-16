package com.aiflow.engine.integration;

import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.config.OfflineProperties;
import com.aiflow.engine.provider.ChatProvider;
import com.aiflow.engine.provider.EndpointHealth;
import com.aiflow.engine.provider.HealthProbe;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 配置声明的 HTTP 端点 -> provider / 健康状态 / 探活清单的唯一构建入口。
 *
 * <p>chat 端点和 embedding 档位是同一个概念（base-url + 密钥 + 超时 + 熔断 + 探活），
 * 分两处构建必然出现「一边有熔断、一边没有」的不对称，所以统一在这里建：
 * chat 端点由本类直接建，embedding 档位建好后把健康对象交回本类登记探活。
 */
@Component
public class HttpEndpointFactory {

    private static final Logger log = LoggerFactory.getLogger(HttpEndpointFactory.class);

    private final ProviderProperties properties;
    private final ObjectMapper objectMapper;
    private final OfflineEgressGuard egressGuard;
    private final Map<String, EndpointHealth> healthById = new ConcurrentHashMap<>();
    private final List<HealthProbe> probes = new CopyOnWriteArrayList<>();
    private final Map<String, ChatProvider> chatProviders = new LinkedHashMap<>();
    private final List<EndpointView> chatEndpoints = new ArrayList<>();

    /** 供不启用 Spring 的 provider/路由测试使用，默认不启用离线门禁。 */
    public HttpEndpointFactory(ProviderProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, OfflineProperties.disabled());
    }

    @Autowired
    public HttpEndpointFactory(ProviderProperties properties, ObjectMapper objectMapper,
                               OfflineProperties offlineProperties) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.egressGuard = new OfflineEgressGuard(offlineProperties);
        for (Map.Entry<String, ProviderProperties.Endpoint> entry : properties.endpoints().entrySet()) {
            String id = entry.getKey();
            ProviderProperties.Endpoint endpoint = entry.getValue();
            String tier = endpointTier(endpoint);
            boolean allowed = egressGuard.allows(id, tier, endpoint.baseUrl());
            EndpointHealth health = health(id, endpoint.enabled() && allowed);
            if (egressGuard.enabled() && endpoint.enabled() && allowed && endpoint.configured()
                    && properties.probe().enabled()) {
                health.markPending();
            }
            chatEndpoints.add(new EndpointView(id, endpoint.model(), endpoint.baseUrl(), endpoint.enabled() && allowed,
                    endpoint.configured() && allowed, endpoint.capabilities(), endpoint.tier(), health));
            if (!endpoint.enabled()) {
                log.info("provider endpoint {} 已禁用，跳过", id);
                continue;
            }
            if (!allowed) {
                log.info("provider endpoint {} 已被离线出网门禁跳过：{}", id, egressGuard.rejectionReason());
                continue;
            }
            if (!endpoint.configured()) {
                log.info("provider endpoint {} 未配置 base-url，跳过（密钥/地址只从环境变量注入，不写进仓库）", id);
                continue;
            }
            registerProbe(id, health, endpoint.baseUrl(), endpoint.apiKey(), endpoint.model(), endpoint.probeTimeoutMs());
            chatProviders.put(id, new OpenAiCompatibleChatProvider(id, endpoint, health, objectMapper));
            log.info("provider endpoint {} 已装配 base-url={} model={} capabilities={}", id, endpoint.baseUrl(),
                    endpoint.model(), endpoint.capabilities());
        }
    }

    /** 端点健康对象：同一 id 只建一次，重复调用返回同一个实例。 */
    public EndpointHealth health(String id, boolean enabled) {
        return healthById.computeIfAbsent(id, key -> new EndpointHealth(key, enabled,
                properties.breaker().failureThreshold(), properties.breaker().cooldownMs()));
    }

    /**
     * 登记一个探活项；探活关闭时返回 null（熔断仍按真实调用失败计数）。
     *
     * <p>{@code model} 会参与探活判定：端点在线但模型列表里没有它，同样算不可用。刚装好本地推理服务、
     * 模型还没拉下来时，这一步能避免把「服务活着」误报成「这一档能用」。
     */
    public HealthProbe registerProbe(String id, EndpointHealth health, String baseUrl, String apiKey, String model) {
        return registerProbe(id, health, baseUrl, apiKey, model, properties.probe().timeoutMs());
    }

    /** 同 {@link #registerProbe(String, EndpointHealth, String, String, String)}，但用指定的探活超时。 */
    public HealthProbe registerProbe(String id, EndpointHealth health, String baseUrl, String apiKey, String model,
                                     long timeoutMs) {
        if (!properties.probe().enabled() || baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        long effective = timeoutMs > 0 ? timeoutMs : properties.probe().timeoutMs();
        EndpointProbe probe = new EndpointProbe(id, health, baseUrl, apiKey, effective, model);
        probes.add(probe);
        return probe;
    }

    /** provider id -> provider，**保持配置声明顺序**：同档位多端点时，顺序就是主备顺序。 */
    public Map<String, ChatProvider> chatProviders() {
        return Collections.unmodifiableMap(chatProviders);
    }

    public List<EndpointView> chatEndpoints() {
        return List.copyOf(chatEndpoints);
    }

    public List<HealthProbe> probes() {
        return List.copyOf(probes);
    }

    public ProviderProperties properties() {
        return properties;
    }

    public OfflineEgressGuard egressGuard() {
        return egressGuard;
    }

    /** 地址判档：localhost 家族算本地档。chat 端点与 embedding 档位共用同一判据。 */
    public static String classifyTier(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "cloud";
        }
        String normalized = baseUrl.toLowerCase();
        return normalized.contains("localhost") || normalized.contains("127.0.0.1") || normalized.contains("[::1]")
                ? "local"
                : "cloud";
    }

    private static String endpointTier(ProviderProperties.Endpoint endpoint) {
        if (endpoint.tier() != null && !endpoint.tier().isBlank()) {
            return endpoint.tier();
        }
        return classifyTier(endpoint.baseUrl());
    }

    public record EndpointView(String id, String model, String baseUrl, boolean enabled, boolean configured,
                               Set<String> capabilities, String declaredTier, EndpointHealth health) {

        /** 配置声明优先；没声明才按地址推导（本机算 local，其余算 cloud）。 */
        public String tier() {
            return declaredTier == null || declaredTier.isBlank() ? classifyTier(baseUrl) : declaredTier.strip().toLowerCase();
        }
    }
}
