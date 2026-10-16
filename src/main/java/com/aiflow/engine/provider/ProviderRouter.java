package com.aiflow.engine.provider;

import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.integration.HttpEndpointFactory;
import com.aiflow.engine.node.RunCanceledException;
import com.aiflow.engine.run.RunLeaseLostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 分级路由：按配置的 order 选档，并在调用失败时自动退到下一档；接近额度上限时可优先便宜档位。
 *
 * <p>两条路径的区别很重要：
 * <ul>
 *   <li>{@link #route(Set)} 只做选择，不发起调用 —— 单测与只读场景用。</li>
 *   <li>{@link #chat(Set, ChatRequest, Consumer)} 按优先级依次尝试，失败换下一档 —— 真实链路用。</li>
 * </ul>
 *
 * <p>换档的三条规则：用户主动取消（{@link RunCanceledException}）不换档，那不是故障；
 * 已经向用户推送过增量（SSE delta）的 provider 失败后不换档，否则两段输出会拼在一起；
 * 所有档位都不可用（探活失败或熔断打开）时仍然按顺序试着调用一遍 —— 健康状态是「大概率」，
 * 不是「绝对」，让兜底档位因为一条过期的失败记录而彻底消失，比多花一次超时更糟。
 */
@Component
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final Map<String, ChatProvider> providers;
    /** provider id -> 档位名（local / cloud），来自端点配置；非 HTTP 端点（mock）没有视图，按 id 兜底。 */
    private final Map<String, String> tierByProviderId;
    private final ProviderProperties properties;

    public ProviderRouter(List<ChatProvider> providerList, ProviderProperties properties) {
        this(providerList, properties, null);
    }

    @Autowired
    public ProviderRouter(List<ChatProvider> providerList, ProviderProperties properties,
                          HttpEndpointFactory endpoints) {
        Map<String, ChatProvider> registered = new LinkedHashMap<>();
        for (ChatProvider provider : providerList) {
            registered.put(provider.id(), provider);
        }
        Map<String, String> tiers = new HashMap<>();
        if (endpoints != null) {
            registered.putAll(endpoints.chatProviders());
            for (HttpEndpointFactory.EndpointView view : endpoints.chatEndpoints()) {
                tiers.put(view.id(), view.tier());
            }
        }
        this.providers = Collections.unmodifiableMap(registered);
        this.tierByProviderId = Map.copyOf(tiers);
        this.properties = properties;
    }

    /** 选择一档（不发调用）。优先健康的档位；全部不健康时返回优先级最高的一档而不是报「无可用」。 */
    public ChatProvider route(Set<String> requiredCapabilities) {
        List<ChatProvider> candidates = candidates(requiredCapabilities);
        if (candidates.isEmpty()) {
            throw new CapabilityUnavailableException(requiredCapabilities);
        }
        return candidates.get(0);
    }

    /** 按 order 依次调用，失败自动降级到下一档。 */
    public ChatOutcome chat(Set<String> requiredCapabilities, ChatRequest request, Consumer<String> sink) {
        return chat(requiredCapabilities, request, sink, false);
    }

    /**
     * 同 {@link #chat(Set, ChatRequest, Consumer)}，外加一个「优先便宜档位」的开关。
     *
     * <p>{@code preferCheapTiers=true} 时把配置里的便宜档位（{@code aiflow.provider.cheap-tiers}）
     * 提到候选序列前面：用户额度接近上限时，宁可慢一点、差一点，也别继续用最贵的档位。
     * 只影响尝试顺序，不改变「谁健康用谁」与「失败换下一档」的规则。
     */
    public ChatOutcome chat(Set<String> requiredCapabilities, ChatRequest request, Consumer<String> sink,
                            boolean preferCheapTiers) {
        List<ChatProvider> ordered = ordered(requiredCapabilities);
        if (preferCheapTiers) {
            ordered = cheapFirst(ordered);
        }
        List<ChatProvider> healthy = ordered.stream().filter(ChatProvider::healthy).toList();
        List<ChatProvider> candidates = healthy.isEmpty() ? ordered : healthy;
        // 探活已经判定挂掉的档位不参与尝试（省一次必然失败的等待），但要在结果里留名：
        // 否则「回答为什么来自 mock」这件事只能靠翻日志。
        List<String> skipped = healthy.isEmpty() ? List.of()
                : ordered.stream().filter(provider -> !provider.healthy()).map(ChatProvider::id).toList();
        if (candidates.isEmpty()) {
            throw new CapabilityUnavailableException(requiredCapabilities);
        }
        List<AllProvidersFailedException.FailedAttempt> failures = new ArrayList<>();
        for (ChatProvider provider : candidates) {
            AtomicBoolean emitted = new AtomicBoolean(false);
            Consumer<String> guarded = chunk -> {
                if (chunk == null || chunk.isEmpty()) {
                    return;
                }
                emitted.set(true);
                if (sink != null) {
                    sink.accept(chunk);
                }
            };
            try {
                ChatResponse response = provider.chat(request, guarded);
                return new ChatOutcome(response, provider.id(), List.copyOf(failures), skipped);
            } catch (RunCanceledException | RunLeaseLostException e) {
                throw e;
            } catch (RuntimeException e) {
                failures.add(new AllProvidersFailedException.FailedAttempt(provider.id(), describe(e)));
                log.warn("provider={} 调用失败，尝试下一档（已推送增量={}）: {}", provider.id(), emitted.get(),
                        e.toString());
                if (emitted.get()) {
                    throw new AllProvidersFailedException(requiredCapabilities, failures, true);
                }
            }
        }
        throw new AllProvidersFailedException(requiredCapabilities, failures);
    }

    public List<ChatProvider> all() {
        return List.copyOf(providers.values());
    }

    public Set<String> providerIds() {
        return providers.keySet();
    }

    public ProviderProperties properties() {
        return properties;
    }

    private List<ChatProvider> candidates(Set<String> requiredCapabilities) {
        List<ChatProvider> matching = ordered(requiredCapabilities);
        List<ChatProvider> healthy = matching.stream().filter(ChatProvider::healthy).toList();
        return healthy.isEmpty() ? matching : healthy;
    }

    /** 稳定分区：便宜档位整体前移，其余保持原有优先级顺序 —— 顺序只在便宜的这批内部重新排。 */
    private List<ChatProvider> cheapFirst(List<ChatProvider> ordered) {
        List<String> cheapTiers = properties.cheapTiers();
        if (cheapTiers.isEmpty()) {
            return ordered;
        }
        List<ChatProvider> cheap = new ArrayList<>();
        List<ChatProvider> rest = new ArrayList<>();
        for (ChatProvider provider : ordered) {
            if (cheapTiers.contains(tierOf(provider))) {
                cheap.add(provider);
            } else {
                rest.add(provider);
            }
        }
        cheap.addAll(rest);
        return cheap;
    }

    /** 档位名：HTTP 端点用配置的 tier（留空时由 base-url 推导），非端点（mock）用 id。 */
    private String tierOf(ChatProvider provider) {
        String tier = tierByProviderId.get(provider.id());
        if (tier != null && !tier.isBlank()) {
            return tier;
        }
        return "mock".equals(provider.id()) ? "mock" : provider.id();
    }

    private List<ChatProvider> ordered(Set<String> requiredCapabilities) {
        List<ChatProvider> matching = new ArrayList<>();
        for (ChatProvider provider : providers.values()) {
            if (!provider.capabilities().containsAll(requiredCapabilities)) {
                continue;
            }
            if (isMockForced() && !"mock".equals(provider.id())) {
                continue;
            }
            matching.add(provider);
        }
        // 稳定排序：同优先级的端点保持声明顺序（配置里谁写在前面先用谁）
        matching.sort(Comparator.comparingInt(provider -> priority(provider.id())));
        return matching;
    }

    private boolean isMockForced() {
        return "mock".equalsIgnoreCase(properties.mode());
    }

    /**
     * 档位优先级（order 里的下标），供状态接口排序展示与路由排序使用。
     *
     * <p>order 配的是**档位顺序**（cloud / local / mock），而端点 id 是任意的（ark / qwen / cloud）。
     * 所以先按 id 匹配，匹配不到再按端点声明的档位名匹配。只按 id 匹配的后果很具体：
     * 新加的云端端点（id=ark、tier=cloud）在 order 里找不到自己，落到 Integer.MAX_VALUE，
     * 排在 mock 后面 —— 一个永远轮不到的兜底。
     */
    public int priorityOf(String providerId) {
        return priority(providerId);
    }

    private int priority(String providerId) {
        int byId = indexOfOrder(providerId);
        if (byId >= 0) {
            return byId;
        }
        String tier = tierByProviderId.get(providerId);
        if (tier != null && !tier.isBlank()) {
            int byTier = indexOfOrder(tier);
            if (byTier >= 0) {
                return byTier;
            }
        }
        return Integer.MAX_VALUE;
    }

    private int indexOfOrder(String name) {
        String[] order = properties.order();
        for (int index = 0; index < order.length; index++) {
            if (order[index].equalsIgnoreCase(name)) {
                return index;
            }
        }
        return -1;
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * 一次成功调用：谁答的 + 试过但失败的档位 + 探活判死直接跳过的档位。
     * 三者合起来才能回答「这次回答到底经过了多少层降级」。
     */
    public record ChatOutcome(ChatResponse response, String providerId,
                              List<AllProvidersFailedException.FailedAttempt> failedAttempts,
                              List<String> skippedProviders) {
    }
}
