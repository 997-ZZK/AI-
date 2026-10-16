package com.aiflow.engine.provider;

import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.integration.HttpEndpointFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同档位多端点的排序规则。
 *
 * <p>{@code order} 里写的是**档位名**（cloud / local / mock），端点 id 是任意的（ark / qwen / cloud）。
 * 只按 id 匹配会让新端点掉到表尾 —— 排在 mock 后面，永远轮不到。这里把规则钉住：
 * 先按 id 匹配，再按端点声明的档位名匹配；同档位的多个端点保持配置声明顺序（主备语义）。
 */
class ProviderRouterTierOrderTest {

    @Test
    void endpointIdUnknownButTierOrdered() {
        Map<String, ProviderProperties.Endpoint> endpoints = new LinkedHashMap<>();
        endpoints.put("cloud", endpoint("cloud", "https://cloud.example/v1"));
        endpoints.put("ark", endpoint("cloud", "https://ark.example/api/v3"));
        ProviderProperties properties = properties(endpoints);
        ProviderRouter router = new ProviderRouter(List.of(), properties, factory(properties));

        assertThat(router.priorityOf("ark")).isEqualTo(router.priorityOf("cloud"));
        assertThat(router.priorityOf("ark")).isLessThan(router.priorityOf("mock"));
        assertThat(router.route(Set.of(Capabilities.CHAT)).id()).isEqualTo("cloud");
    }

    @Test
    void sameTierFollowsDeclarationOrder() {
        Map<String, ProviderProperties.Endpoint> endpoints = new LinkedHashMap<>();
        endpoints.put("ark", endpoint("cloud", "https://ark.example/api/v3"));
        endpoints.put("cloud", endpoint("cloud", "https://cloud.example/v1"));
        ProviderProperties properties = properties(endpoints);
        ProviderRouter router = new ProviderRouter(List.of(), properties, factory(properties));

        assertThat(router.route(Set.of(Capabilities.CHAT)).id()).isEqualTo("ark");
    }

    @Test
    void unknownTierFallsToTail() {
        Map<String, ProviderProperties.Endpoint> endpoints = new LinkedHashMap<>();
        endpoints.put("cloud", endpoint("cloud", "https://cloud.example/v1"));
        endpoints.put("edge", endpoint("edge", "https://edge.example/v1"));
        ProviderProperties properties = properties(endpoints);
        ProviderRouter router = new ProviderRouter(List.of(), properties, factory(properties));

        assertThat(router.priorityOf("edge")).isEqualTo(Integer.MAX_VALUE);
        assertThat(router.priorityOf("edge")).isGreaterThan(router.priorityOf("mock"));
    }

    @Test
    void blankTierDerivedFromAddress() {
        Map<String, ProviderProperties.Endpoint> endpoints = new LinkedHashMap<>();
        endpoints.put("ollama", endpoint("", "http://localhost:11434/v1"));
        ProviderProperties properties = properties(endpoints);
        ProviderRouter router = new ProviderRouter(List.of(), properties, factory(properties));

        assertThat(router.priorityOf("ollama")).isEqualTo(router.priorityOf("local"));
    }

    private static ProviderProperties properties(Map<String, ProviderProperties.Endpoint> endpoints) {
        ProviderProperties defaults = ProviderProperties.defaults();
        return new ProviderProperties("auto", new String[]{"cloud", "local", "mock"}, defaults.cheapTiers(),
                defaults.mock(), endpoints, defaults.probe(), defaults.breaker());
    }

    private static ProviderProperties.Endpoint endpoint(String tier, String baseUrl) {
        return new ProviderProperties.Endpoint(baseUrl, "test-model", "", Set.of("chat"), true, 5000, tier, "", 0);
    }

    private static HttpEndpointFactory factory(ProviderProperties properties) {
        return new HttpEndpointFactory(properties, new ObjectMapper());
    }
}
