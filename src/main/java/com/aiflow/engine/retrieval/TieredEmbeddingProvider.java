package com.aiflow.engine.retrieval;

import com.aiflow.engine.provider.EndpointHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分级 embedding：按顺序尝试多个档位，前档失败自动落到后档（本地 Ollama 兜底 / 云端加速）。
 *
 * <p>为什么可以互为兜底：所有档位必须跑**同一个模型**（同一份权重、同一维度），
 * 向量才在同一个空间里可比。构建期就校验 model 一致，不一致直接启动失败 ——
 * 让两个不同模型的向量互相顶替，检索不会报错，只会静默给出错误的相似度，那比宕机更难查。
 *
 * <p>{@link #id()} 用的是模型身份（{@code http-embedding:bge-m3}），不是「哪一档服务了这一票」：
 * 数据库里的向量标记必须与「谁生成的」无关，否则同一份索引在断网前后会被判成两个向量空间。
 */
public class TieredEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(TieredEmbeddingProvider.class);

    private final String id;
    private final int dimensions;
    private final List<Tier> tiers;

    public TieredEmbeddingProvider(String id, int dimensions, List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("分级 embedding 至少需要一个档位");
        }
        this.id = id;
        this.dimensions = dimensions;
        this.tiers = List.copyOf(tiers);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public boolean healthy() {
        return tiers.stream().anyMatch(tier -> tier.health().available());
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        for (Tier tier : tiers) {
            if (!tier.health().available()) {
                errors.add(tier.name() + "=熔断中");
                continue;
            }
            try {
                List<float[]> vectors = tier.provider().embedAll(texts);
                if (vectors.size() != texts.size()) {
                    throw new RetrievalException("embedding 返回条数与请求不一致: " + vectors.size() + " != "
                            + texts.size());
                }
                tier.health().recordSuccess();
                return vectors;
            } catch (RuntimeException e) {
                tier.health().recordFailure(e.getMessage());
                errors.add(tier.name() + "=" + e.getMessage());
                log.warn("embedding 档位 {} 失败，尝试下一档: {}", tier.name(), e.toString());
            }
        }
        throw new RetrievalException("所有 embedding 档位都失败: " + String.join("; ", errors));
    }

    public List<Tier> tiers() {
        return tiers;
    }

    public List<Map<String, Object>> status() {
        List<Map<String, Object>> view = new ArrayList<>(tiers.size());
        for (Tier tier : tiers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tier.name());
            item.put("kind", tier.kind());
            item.put("provider", tier.provider().id());
            item.put("available", tier.health().available());
            item.put("state", tier.health().snapshot().state());
            item.put("lastError", tier.health().snapshot().lastError());
            view.add(item);
        }
        return view;
    }

    /** kind = local / cloud，只用于状态展示与降级话术。 */
    /**
     * 一个档位。{@code baseUrl} 保留在运行时配置对象中供 provider 使用；诊断日志只输出受控元数据，
     * 不把地址、路径或可能存在于 query/userinfo 中的凭证写入日志。
     */
    public record Tier(String name, String kind, EmbeddingProvider provider, EndpointHealth health, String baseUrl) {
    }
}
