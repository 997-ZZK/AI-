package com.aiflow.engine.config;

import com.aiflow.engine.ingest.DocumentParser;
import com.aiflow.engine.ingest.DocumentExtractor;
import com.aiflow.engine.ingest.OcrProvider;
import com.aiflow.engine.ingest.TextChunker;
import com.aiflow.engine.integration.HttpEmbeddingProvider;
import com.aiflow.engine.integration.HttpEndpointFactory;
import com.aiflow.engine.integration.HttpKnowledgeBaseProvider;
import com.aiflow.engine.integration.OfflineEgressGuard;
import com.aiflow.engine.persistence.retrieval.JpaKnowledgeBaseCorpus;
import com.aiflow.engine.persistence.retrieval.LocalRetrievalProvider;
import com.aiflow.engine.persistence.retrieval.PgVectorEmbeddingStore;
import com.aiflow.engine.persistence.repo.DocumentChunkRepository;
import com.aiflow.engine.provider.EndpointHealth;
import com.aiflow.engine.retrieval.ContextPromptAssembler;
import com.aiflow.engine.retrieval.CosineScoreNormalizer;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.EmbeddingStore;
import com.aiflow.engine.retrieval.LinearRangeScoreNormalizer;
import com.aiflow.engine.retrieval.RagRetrievalService;
import com.aiflow.engine.retrieval.RetrievalProvider;
import com.aiflow.engine.retrieval.ScoreNormalizer;
import com.aiflow.engine.retrieval.TieredEmbeddingProvider;
import com.aiflow.engine.retrieval.memory.InMemoryEmbeddingStore;
import com.aiflow.engine.retrieval.mock.MockEmbeddingProvider;
import com.aiflow.engine.retrieval.mock.MockKnowledgeBaseProvider;
import com.aiflow.engine.retrieval.post.RetrievalParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 链路装配：embedding provider、向量存储、检索来源、后处理参数、入库流水线组件。
 * 默认组合（mock embedding + pgvector 存储 + local 来源）离线可跑，换真实服务只改配置。
 */
@Configuration
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalConfig {

    private static final Logger log = LoggerFactory.getLogger(RetrievalConfig.class);

    @Bean
    public ContextPromptAssembler contextPromptAssembler() {
        return new ContextPromptAssembler();
    }

    @Bean
    @ConditionalOnProperty(name = "aiflow.retrieval.embedding.mode", havingValue = "mock", matchIfMissing = true)
    public EmbeddingProvider mockEmbeddingProvider(RetrievalProperties properties) {
        return new MockEmbeddingProvider("mock-embedding", properties.embedding().dimensions());
    }

    @Bean
    @ConditionalOnProperty(name = "aiflow.retrieval.embedding.mode", havingValue = "http")
    public EmbeddingProvider httpEmbeddingProvider(RetrievalProperties properties, HttpEndpointFactory endpoints,
                                                   OfflineProperties offlineProperties) {
        return buildEmbeddingChain(properties.embedding(), endpoints, offlineProperties);
    }

    /**
     * 把配置里的 embedding 档位编成分级链条。单档（没写 tiers）等价于一档链条，行为与旧版一致。
     *
     * <p>模型一致性在这里强制校验：不同模型的向量余弦算得出来但毫无意义，静默混用比启动失败危险得多。
     */
    static TieredEmbeddingProvider buildEmbeddingChain(RetrievalProperties.Embedding embedding,
                                                       HttpEndpointFactory endpoints) {
        return buildEmbeddingChain(embedding, endpoints, OfflineProperties.disabled());
    }

    static TieredEmbeddingProvider buildEmbeddingChain(RetrievalProperties.Embedding embedding,
                                                       HttpEndpointFactory endpoints,
                                                       OfflineProperties offlineProperties) {
        List<RetrievalProperties.EmbeddingTier> configured = embedding.tiers().isEmpty()
                ? List.of(new RetrievalProperties.EmbeddingTier("default", embedding.baseUrl(), embedding.model(),
                        embedding.apiKey(), true, embedding.timeoutMs()))
                : embedding.tiers();
        List<TieredEmbeddingProvider.Tier> tiers = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int index = 0; index < configured.size(); index++) {
            RetrievalProperties.EmbeddingTier tier = configured.get(index);
            String name = tier.id() == null || tier.id().isBlank() ? "tier-" + (index + 1) : tier.id();
            if (!tier.enabled() || tier.baseUrl() == null || tier.baseUrl().isBlank()) {
                skipped.add(name);
                continue;
            }
            String tierKind = HttpEndpointFactory.classifyTier(tier.baseUrl());
            OfflineEgressGuard egressGuard = new OfflineEgressGuard(offlineProperties);
            if (!egressGuard.allows(name, tierKind, tier.baseUrl())) {
                skipped.add(name + "(offline-egress)");
                continue;
            }
            String model = tier.model() == null || tier.model().isBlank() ? embedding.model() : tier.model();
            if (!model.equals(embedding.model())) {
                throw new IllegalStateException("embedding 档位 " + name + " 的 model=" + model
                        + " 与 aiflow.retrieval.embedding.model=" + embedding.model()
                        + " 不一致：不同模型的向量不在同一向量空间，不能互为兜底（换模型要换全部档位并重建索引）");
            }
            long timeout = tier.timeoutMs() > 0 ? tier.timeoutMs() : embedding.timeoutMs();
            EndpointHealth health = endpoints.health("embedding:" + name, true);
            if (offlineProperties.enabled() && endpoints.properties().probe().enabled()) {
                health.markPending();
            }
            endpoints.registerProbe("embedding:" + name, health, tier.baseUrl(), tier.apiKey(), model);
            tiers.add(new TieredEmbeddingProvider.Tier(name, HttpEndpointFactory.classifyTier(tier.baseUrl()),
                    // 这一层只负责产出向量，阈值（Embedding.minScore）由检索层读上层配置，传 -1 占位
                    new HttpEmbeddingProvider(new RetrievalProperties.Embedding("http", -1, embedding.dimensions(),
                            model, tier.baseUrl(), tier.apiKey(), timeout, List.of())),
                    health, tier.baseUrl()));
        }
        if (tiers.isEmpty()) {
            throw new IllegalStateException("embedding.mode=http 但没有任何可用档位：检查 tiers 的 enabled / base-url/离线出网门禁");
        }
        if (!skipped.isEmpty()) {
            log.info("embedding 档位 {} 未启用（enabled=false 或 base-url 为空），当前链条只含 {}", skipped,
                    tiers.stream().map(TieredEmbeddingProvider.Tier::name).toList());
        }
        // 把生效的链条印出来：向量列只记模型名、不记主机，同维不同模型混进一条链是静默错误。
        // 日志只保留档位、类型、provider 与模型；base-url 可能带 query/userinfo 形式的凭证，不能落日志。
        log.info("embedding 链条（按优先级）：{}", tiers.stream()
                .map(item -> item.name() + "[" + item.kind() + "] " + item.provider().id()
                        + " model=" + embedding.model())
                .toList());
        return new TieredEmbeddingProvider("http-embedding:" + embedding.model(), embedding.dimensions(), tiers);
    }

    @Bean
    @ConditionalOnProperty(name = "aiflow.retrieval.embedding-store", havingValue = "pgvector", matchIfMissing = true)
    public EmbeddingStore pgVectorEmbeddingStore(JdbcTemplate jdbcTemplate, RetrievalProperties properties) {
        RetrievalProperties.Ann ann = properties.ann();
        return new PgVectorEmbeddingStore(jdbcTemplate, properties.embedding().dimensions(), ann.efSearch(),
                ann.iterativeScan());
    }

    @Bean
    @ConditionalOnProperty(name = "aiflow.retrieval.embedding-store", havingValue = "memory")
    public EmbeddingStore inMemoryEmbeddingStore() {
        return new InMemoryEmbeddingStore();
    }

    @Bean
    public RetrievalProvider localRetrievalProvider(DocumentChunkRepository chunkRepository,
                                                    EmbeddingStore embeddingStore,
                                                    EmbeddingProvider embeddingProvider,
                                                    RetrievalProperties properties) {
        RetrievalProperties.Ann ann = properties.ann();
        int candidates = Math.max(ann.candidates(), properties.maxK());
        if (ann.enabled() && ann.candidates() < properties.maxK()) {
            // 候选宽度必须覆盖 maxK：断崖 top-k 是在候选集里挑的，候选比 maxK 还窄就永远挑不满。
            // 不静默放过，也不直接失败（能跑，只是召回受限于配置），启动期告警让人看得见。
            log.warn("aiflow.retrieval.ann.candidates={} 小于 max-k={}，本次按 max-k={} 生效；"
                            + "建议配到 max-k 的若干倍以吸收 ANN 的近似损失",
                    ann.candidates(), properties.maxK(), candidates);
        }
        // 启动期必须留一条「这次到底走哪条路」的日志：两条路径结果同口径，看检索结果分不出来，
        // 排查性能问题时第一个要问的就是它。
        if (ann.enabled() && embeddingStore.supportsAnn()) {
            log.info("本地检索候选集走 ANN（store={}, candidates={}, ef-search={}, iterative-scan={}）",
                    embeddingStore.id(), candidates, ann.efSearch(),
                    ann.iterativeScan().isEmpty() ? "默认" : ann.iterativeScan());
        } else if (ann.enabled()) {
            log.info("ann.enabled=true 但 embedding-store={} 不支持 ANN，本地检索走精确余弦", embeddingStore.id());
        } else {
            log.info("本地检索候选集走精确余弦（ann.enabled=false；store={}）", embeddingStore.id());
        }
        return new LocalRetrievalProvider(chunkRepository, embeddingStore, embeddingProvider,
                ann.enabled(), candidates);
    }

    @Bean
    @ConditionalOnProperty(name = "aiflow.retrieval.cloud.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "aiflow.offline.enabled", havingValue = "false", matchIfMissing = true)
    public RetrievalProvider cloudKnowledgeBaseProvider(RetrievalProperties properties,
                                                        EmbeddingProvider embeddingProvider,
                                                        DocumentChunkRepository chunkRepository) {
        RetrievalProperties.Cloud cloud = properties.cloud();
        if ("http".equalsIgnoreCase(cloud.mode())) {
            return new HttpKnowledgeBaseProvider(cloud);
        }
        return new MockKnowledgeBaseProvider(cloud.source(),
                new JpaKnowledgeBaseCorpus(chunkRepository, cloud.source()), embeddingProvider, cloud.scoreMax());
    }

    @Bean
    public RagRetrievalService ragRetrievalService(EmbeddingProvider embeddingProvider,
                                                   List<RetrievalProvider> providers,
                                                   RetrievalProperties properties,
                                                   ContextPromptAssembler assembler) {
        return new RagRetrievalService(embeddingProvider, providers, normalizers(properties), params(properties),
                assembler);
    }

    @Bean
    public DocumentParser documentParser(ObjectMapper objectMapper) {
        return new DocumentParser(objectMapper);
    }

    @Bean
    public TextChunker textChunker() {
        return new TextChunker();
    }

    @Bean
    public DocumentExtractor documentExtractor(OcrProvider ocrProvider, IngestProperties ingestProperties) {
        return new DocumentExtractor(ocrProvider, ingestProperties);
    }

    private static List<ScoreNormalizer> normalizers(RetrievalProperties properties) {
        List<ScoreNormalizer> normalizers = new ArrayList<>();
        normalizers.add(new CosineScoreNormalizer(LocalRetrievalProvider.SOURCE));
        if (properties.cloud().enabled()) {
            normalizers.add(new LinearRangeScoreNormalizer(properties.cloud().source(),
                    properties.cloud().scoreMin(), properties.cloud().scoreMax()));
        }
        return normalizers;
    }

    private static RetrievalParams params(RetrievalProperties properties) {
        double minScore = effectiveMinScore(properties);
        logMinScoreSource(properties, minScore);
        return new RetrievalParams(minScore, properties.topK(), properties.minK(), properties.maxK(),
                properties.cliffRatio(), properties.mmrLambda(), properties.mergeAdjacent());
    }

    /**
     * 有效检索阈值：{@code embedding.min-score} 优先（阈值是向量分布的属性，跟着向量模型走），
     * 没配（{@code < 0}）才回落到全局 {@code aiflow.retrieval.min-score}。
     */
    static double effectiveMinScore(RetrievalProperties properties) {
        double fromEmbedding = properties.embedding().minScore();
        return fromEmbedding >= 0 ? fromEmbedding : properties.minScore();
    }

    /**
     * 阈值用错量纲不会报错，只会让「低相似度丢弃」悄悄失效，所以启动期必须把生效值印出来。
     * mode=http 却没配 embedding.min-score 时额外告警：那多半是用着 mock 量纲的阈值在跑真实向量。
     */
    private static void logMinScoreSource(RetrievalProperties properties, double effective) {
        RetrievalProperties.Embedding embedding = properties.embedding();
        if (embedding.minScore() >= 0) {
            log.info("检索阈值 min-score={}（来源 embedding.min-score；mode={} model={}）",
                    effective, embedding.mode(), embedding.model());
            return;
        }
        if ("http".equalsIgnoreCase(embedding.mode())) {
            log.warn("embedding.mode=http 但未配置 aiflow.retrieval.embedding.min-score，"
                    + "正在使用全局 min-score={}。该全局值是按 mock 量纲（确定性哈希）校准的，"
                    + "真实模型上偏松；跑 RagRealEmbeddingCalibrationTest 校准后写到 embedding.min-score，"
                    + "记录见 docs/quality-plan.md", effective);
            return;
        }
        log.info("检索阈值 min-score={}（来源 aiflow.retrieval.min-score；mode={}）", effective, embedding.mode());
    }
}
