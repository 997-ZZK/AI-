package com.aiflow.engine.memory.longterm;

import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.persistence.entity.UserMemoryEntity;
import com.aiflow.engine.persistence.repo.UserMemoryRepository;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 召回归口：query -> 向量 -> 候选（ANN 或精确）-> 阈值 -> 条数 -> 字符预算（设计见 docs/long-memory-design.md §3.4）。
 *
 * <p>实现 {@link LongMemoryRecallPort}，契约是**不抛异常**：向量服务挂了、表还没迁移、query 异常，
 * 一律降级为「没有长期记忆」。装配路径与没开这个功能时完全一致，只是少一段 system 块。
 *
 * <p>阈值口径与检索链路一致（{@code recall-min-score} 优先，空则继承 embedding 档位，再退全局），
 * 启动期把生效值印出来 —— 用错量纲的阈值不会报错，只会让「低相似度丢弃」悄悄失效。
 */
@Service
public class LongMemoryRecallService implements LongMemoryRecallPort {

    private static final Logger log = LoggerFactory.getLogger(LongMemoryRecallService.class);
    private static final long LOG_EVERY = 50;

    private final UserMemoryRepository memoryRepository;
    private final UserMemoryVectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final LongMemoryProperties properties;
    private final RetrievalProperties retrievalProperties;
    private final AtomicLong readFailures = new AtomicLong();

    public LongMemoryRecallService(UserMemoryRepository memoryRepository, UserMemoryVectorStore vectorStore,
                                   EmbeddingProvider embeddingProvider, LongMemoryProperties properties,
                                   RetrievalProperties retrievalProperties) {
        this.memoryRepository = memoryRepository;
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
        this.properties = properties;
        this.retrievalProperties = retrievalProperties;
        log.info("长期记忆召回：min-score={}（来源 {}），embedding={}（{} 维），store={}（ANN {}）",
                effectiveThreshold(), thresholdSource(), embeddingProvider.id(), embeddingProvider.dimensions(),
                vectorStore.id(), vectorStore.supportsAnn() ? "开" : "关（精确余弦）");
    }

    @Override
    public long readFailures() {
        return readFailures.get();
    }

    /** 装配路径用的召回：{@code writeBack=true} 时命中会回写 hits / last_used_at（一次 SQL，失败只 WARN）。 */
    @Override
    public List<RecalledMemory> recall(UUID userId, String query, int topK, boolean writeBack) {
        return recallDetailed(userId, query, topK, writeBack).injected();
    }

    /** preview 用的召回：把候选与丢弃原因一起摊开，且**不回写**观测数据（看一眼不该改变数据）。 */
    public RecallOutcome recallDetailed(UUID userId, String query, int topK, boolean writeBack) {
        List<String> notes = new ArrayList<>();
        double threshold = effectiveThreshold();
        if (properties.recallOff() || userId == null || query == null || query.isBlank() || topK <= 0) {
            return new RecallOutcome(query == null ? "" : query, threshold, List.of(), List.of(), List.of(), notes);
        }
        try {
            // 一条都没有就**不去调 embedding**：召回默认开，这个便宜的短路让「什么都没存过的用户」零成本
            if (memoryRepository.countByUserId(userId) == 0) {
                notes.add("no-entries");
                return new RecallOutcome(query, threshold, List.of(), List.of(), List.of(), notes);
            }
            float[] queryVector = embeddingProvider.embed(query);
            Vectors.requireDimensions(queryVector, embeddingProvider.dimensions(), "embedding " + embeddingProvider.id());
            Map<UUID, Double> scores = score(userId, queryVector, topK, notes);
            if (scores.isEmpty()) {
                return new RecallOutcome(query, threshold, List.of(), List.of(), List.of(), notes);
            }
            Map<UUID, UserMemoryEntity> entities = loadEntities(scores.keySet());
            List<RecalledMemory> above = new ArrayList<>();
            List<Dropped> dropped = new ArrayList<>();
            scores.forEach((id, score) -> {
                UserMemoryEntity entity = entities.get(id);
                if (entity == null || entity.getValue() == null || entity.getValue().isBlank()) {
                    return;
                }
                RecalledMemory recalled = new RecalledMemory(id, entity.getKind(), entity.getDisplayKey(),
                        entity.getValue(), score);
                if (score < threshold) {
                    dropped.add(new Dropped(id, entity.getDisplayKey(), score, "below-threshold"));
                    return;
                }
                above.add(recalled);
            });
            above.sort(Comparator.comparingDouble(RecalledMemory::score).reversed()
                    .thenComparing(recalled -> recalled.id().toString()));
            int limit = Math.max(1, topK);
            List<RecalledMemory> top = above;
            if (above.size() > limit) {
                above.subList(limit, above.size()).forEach(extra ->
                        dropped.add(new Dropped(extra.id(), extra.displayKey(), extra.score(), "over-top-k")));
                top = new ArrayList<>(above.subList(0, limit));
            }
            MemoryRecallBudget.Trimmed trimmed = MemoryRecallBudget.apply(top, properties.recallMaxChars());
            trimmed.dropped().forEach(extra ->
                    dropped.add(new Dropped(extra.id(), extra.displayKey(), extra.score(), "over-budget")));
            if (writeBack && !trimmed.kept().isEmpty()) {
                touch(trimmed.kept());
            }
            return new RecallOutcome(query, threshold, trimmed.kept(), List.copyOf(above), List.copyOf(dropped), notes);
        } catch (RuntimeException e) {
            recordFailure("recall", e);
            notes.add("recall-failed");
            return new RecallOutcome(query, threshold, List.of(), List.of(), List.of(), notes);
        }
    }

    /**
     * 候选打分：先走 ANN（支持时），ANN 返回空则回退精确余弦 ——
     * 带 {@code user_id} 过滤的 ANN 查询可能被筛空（HNSW 只扫有限的候选），把「没扫到」当成「没有」会静默漏掉记忆。
     */
    private Map<UUID, Double> score(UUID userId, float[] queryVector, int topK, List<String> notes) {
        int width = Math.max(topK, properties.recallTopK());
        if (vectorStore.supportsAnn()) {
            List<UserMemoryVectorStore.Hit> hits = vectorStore.search(userId, embeddingProvider.id(), queryVector, width);
            if (hits != null && !hits.isEmpty()) {
                Map<UUID, Double> scores = new LinkedHashMap<>();
                hits.forEach(hit -> scores.put(hit.memoryId(), hit.cosine()));
                return scores;
            }
            notes.add("ann-empty-fallback-exact");
        }
        Map<UUID, float[]> stored = vectorStore.load(userId, embeddingProvider.id());
        Map<String, Long> models = vectorStore.indexedModels(userId);
        models.keySet().stream().filter(model -> !model.equals(embeddingProvider.id())).findFirst()
                .ifPresent(model -> notes.add("stale-embeddings:" + model));
        Map<UUID, Double> scores = new LinkedHashMap<>();
        stored.forEach((id, vector) -> scores.put(id, Vectors.cosine(queryVector, vector)));
        return scores;
    }

    private Map<UUID, UserMemoryEntity> loadEntities(Set<UUID> ids) {
        List<UserMemoryEntity> rows = memoryRepository.findAllById(new LinkedHashSet<>(ids));
        Map<UUID, UserMemoryEntity> entities = new LinkedHashMap<>();
        rows.forEach(row -> entities.put(row.getId(), row));
        return entities;
    }

    private void touch(List<RecalledMemory> kept) {
        try {
            List<UUID> ids = kept.stream().map(RecalledMemory::id).toList();
            memoryRepository.touchHits(ids, Instant.now());
        } catch (RuntimeException e) {
            // 观测数据不值得让一次问答变慢，更不值得让它失败
            log.debug("长期记忆 hits 回写失败（无妨）：{}", e.toString());
        }
    }

    /** query = 本轮输入 +（存在摘要且 {@code recall-query-chars > 0} 时）摘要前 N 字。 */
    public static String buildQuery(String input, String summary, int summaryChars) {
        String text = input == null ? "" : input.strip();
        if (summaryChars <= 0 || summary == null || summary.isBlank()) {
            return text;
        }
        String head = summary.strip();
        if (head.length() > summaryChars) {
            head = head.substring(0, summaryChars);
        }
        return text.isEmpty() ? head : text + "\n" + head;
    }

    /** 生效阈值：{@code recall-min-score} 优先（阈值跟着向量模型走），空则继承检索链路的档位值，再退全局。 */
    public double effectiveThreshold() {
        if (properties.recallMinScore() >= 0) {
            return properties.recallMinScore();
        }
        if (retrievalProperties.embedding().minScore() >= 0) {
            return retrievalProperties.embedding().minScore();
        }
        return retrievalProperties.minScore();
    }

    String thresholdSource() {
        if (properties.recallMinScore() >= 0) {
            return "long-memory.recall-min-score";
        }
        return retrievalProperties.embedding().minScore() >= 0 ? "retrieval.embedding.min-score" : "retrieval.min-score";
    }

    private void recordFailure(String what, RuntimeException e) {
        long count = readFailures.incrementAndGet();
        if (count == 1 || count % LOG_EVERY == 0) {
            log.warn("长期记忆{}失败（第 {} 次，降级为无长期记忆）：{}", what, count, e.toString());
        }
    }

    /** 一次召回的完整结果：注入集 / 过阈候选 / 丢弃（含原因）/ 诊断备注，preview 接口直接摊开它。 */
    public record RecallOutcome(String query, double threshold, List<RecalledMemory> injected,
                                List<RecalledMemory> candidates, List<Dropped> dropped, List<String> notes) {
    }

    public record Dropped(UUID id, String displayKey, double score, String reason) {
    }
}
