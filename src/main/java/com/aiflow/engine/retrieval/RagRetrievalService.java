package com.aiflow.engine.retrieval;

import com.aiflow.engine.retrieval.post.ProcessedRetrieval;
import com.aiflow.engine.retrieval.post.RetrievalParams;
import com.aiflow.engine.retrieval.post.RetrievalPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 检索编排：向量化 -> 多来源取数 -> 分数归一化 -> 融合去重 -> 后处理链 -> 提示词组装。
 *
 * 单个来源失败只记固定分类的警告并继续（部分降级）；所有来源都失败才抛不带原始异常链的
 * {@link RetrievalException}。
 */
public final class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);
    private static final ScoreNormalizer FALLBACK_NORMALIZER = new LinearRangeScoreNormalizer("*", 0, 1);
    private static final String EMBEDDING_FAILURE = "embedding 调用失败";
    private static final String EMBEDDING_CONFIGURATION_FAILURE = "embedding 配置失败";
    private static final String SOURCE_HEALTH_FAILURE = "来源健康检查失败";
    private static final String SOURCE_RETRIEVAL_FAILURE = "来源检索失败";
    private static final String SOURCE_UNHEALTHY = "来源不可用";
    /** 同分判定容差：小于它的分数差视为同分，避免浮点噪声改变来源归属。 */
    private static final double SCORE_TIE_EPSILON = 1e-9;

    private final EmbeddingProvider embeddingProvider;
    private final List<RetrievalProvider> providers;
    private final Map<String, ScoreNormalizer> normalizers;
    private final RetrievalParams params;
    private final ContextPromptAssembler assembler;

    public RagRetrievalService(EmbeddingProvider embeddingProvider, List<RetrievalProvider> providers,
                               List<ScoreNormalizer> normalizers, RetrievalParams params,
                               ContextPromptAssembler assembler) {
        this.embeddingProvider = embeddingProvider;
        this.providers = List.copyOf(providers);
        Map<String, ScoreNormalizer> bySource = new HashMap<>();
        for (ScoreNormalizer normalizer : normalizers) {
            bySource.put(normalizer.source(), normalizer);
        }
        this.normalizers = Map.copyOf(bySource);
        this.params = params;
        this.assembler = assembler;
    }

    public RetrievalResult retrieve(String query, String kbId, Set<String> sources, Integer topK, Double minScore) {
        RetrievalParams effective = params.withTopK(topK).withMinScore(minScore);
        float[] embedding;
        try {
            embedding = embeddingProvider.embed(query);
        } catch (RuntimeException e) {
            throw new RetrievalException(EMBEDDING_FAILURE);
        }
        int embeddingDimensions;
        try {
            embeddingDimensions = embeddingProvider.dimensions();
        } catch (RuntimeException e) {
            throw new RetrievalException(EMBEDDING_CONFIGURATION_FAILURE);
        }
        try {
            Vectors.requireDimensions(embedding, embeddingDimensions, "embedding provider");
        } catch (RuntimeException e) {
            throw new RetrievalException("检索向量维度不符");
        }

        RetrievalQuery retrievalQuery = new RetrievalQuery(query, embedding, kbId, effective.maxK(),
                effective.minScore(), Map.of());
        List<RetrievedChunk> collected = new ArrayList<>();
        List<String> usedSources = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (RetrievalProvider provider : providers) {
            String source = safeSource(provider);
            if (sources != null && !sources.isEmpty() && !sources.contains(source)) {
                continue;
            }
            boolean healthy;
            try {
                healthy = provider.healthy();
            } catch (RuntimeException e) {
                log.warn("retrieval source {} failed: {}", source, SOURCE_HEALTH_FAILURE);
                failures.add(source + ": " + SOURCE_HEALTH_FAILURE);
                continue;
            }
            if (!healthy) {
                failures.add(source + ": " + SOURCE_UNHEALTHY);
                continue;
            }
            try {
                ScoreNormalizer normalizer = normalizers.getOrDefault(source, FALLBACK_NORMALIZER);
                for (RetrievedChunk chunk : provider.retrieve(retrievalQuery)) {
                    collected.add(chunk.withScore(normalizer.normalize(chunk.rawScore()), chunk.rawScore()));
                }
                usedSources.add(source);
            } catch (RuntimeException e) {
                log.warn("retrieval source {} failed: {}", source, SOURCE_RETRIEVAL_FAILURE);
                failures.add(source + ": " + safeFailureDescription(e));
            }
        }
        if (usedSources.isEmpty()) {
            throw new RetrievalException("所有检索来源都不可用: " + String.join("; ", failures));
        }
        List<RetrievedChunk> fused = dedupe(collected);
        ProcessedRetrieval processed = RetrievalPipeline.process(fused, effective);
        AssembledContext assembled = assembler.assemble(processed.chunks());
        log.debug("retrieval kb={} sources={} stats={} embedding={}d",
                kbId, usedSources, processed.stats(), embeddingDimensions);
        return new RetrievalResult(query, kbId, List.copyOf(usedSources), assembled.chunks(),
                assembled.contextBlock(), processed.stats());
    }

    private static String safeSource(RetrievalProvider provider) {
        try {
            String source = provider.source();
            return source == null || source.isBlank() ? "unknown" : source;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String safeFailureDescription(RuntimeException failure) {
        if (failure instanceof RetrievalException retrieval && retrieval.hasSafeMessage()) {
            return retrieval.getMessage();
        }
        return SOURCE_RETRIEVAL_FAILURE;
    }

    /**
     * 融合去重：同一切片（contentHash 相同，或 chunkId 相同）只保留分数更高的那份。
     *
     * 分数差在 {@link #SCORE_TIE_EPSILON} 以内视为同分，保留先注册的来源：本地与云端对同一内容打分只差
     * 归一化往返的浮点误差，若不设容差，1 ULP 的噪声就能把归属从 local 翻到 cloud（实测踩过：
     * 同一片段本地余弦 0.5373564...、云端量程往返后 0.5373564...+1ULP），引用来源与成本会随机漂移。
     */
    private List<RetrievedChunk> dedupe(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> byKey = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            String key = chunk.contentHash() != null && !chunk.contentHash().isBlank()
                    ? chunk.contentHash()
                    : chunk.source() + "|" + chunk.chunkId();
            RetrievedChunk existing = byKey.get(key);
            if (existing == null || chunk.score() > existing.score() + SCORE_TIE_EPSILON) {
                byKey.put(key, chunk);
            }
        }
        return List.copyOf(byKey.values());
    }
}
