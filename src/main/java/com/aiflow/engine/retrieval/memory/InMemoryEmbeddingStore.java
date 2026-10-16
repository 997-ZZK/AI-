package com.aiflow.engine.retrieval.memory;

import com.aiflow.engine.retrieval.EmbeddingStore;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内向量存储：测试与离线降级用（无 pgvector 也能跑完整检索链路）。
 * 数据在重启后丢失，生产用 PgVectorEmbeddingStore。
 *
 * <p>按 kb + provider 两级隔离，与 pgvector 实现（embedding_model 列）语义一致。
 */
public class InMemoryEmbeddingStore implements EmbeddingStore {

    private final Map<String, Map<String, Map<String, float[]>>> byKnowledgeBase = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "memory";
    }

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public void upsertAll(String kbId, String embeddingModel, Map<String, float[]> embeddings) {
        if (embeddings.isEmpty()) {
            return;
        }
        byKnowledgeBase.computeIfAbsent(kbId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(modelKey(embeddingModel), ignored -> new ConcurrentHashMap<>())
                .putAll(embeddings);
    }

    @Override
    public Map<String, float[]> load(String kbId, String embeddingModel) {
        return Map.copyOf(byKnowledgeBase.getOrDefault(kbId, Map.of())
                .getOrDefault(modelKey(embeddingModel), Map.of()));
    }

    @Override
    public Map<String, Long> indexedModels(String kbId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        byKnowledgeBase.getOrDefault(kbId, Map.of()).forEach((model, vectors) -> {
            if (!vectors.isEmpty()) {
                counts.put(model, (long) vectors.size());
            }
        });
        return counts;
    }

    @Override
    public void removeAll(Collection<String> chunkIds) {
        byKnowledgeBase.values().forEach(byModel ->
                byModel.values().forEach(vectors -> chunkIds.forEach(vectors::remove)));
    }

    public Map<String, Integer> sizes() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        byKnowledgeBase.forEach((kbId, byModel) -> {
            int total = byModel.values().stream().mapToInt(Map::size).sum();
            if (total > 0) {
                sizes.put(kbId, total);
            }
        });
        return sizes;
    }

    private static String modelKey(String embeddingModel) {
        return embeddingModel == null || embeddingModel.isBlank() ? UNKNOWN_MODEL : embeddingModel;
    }
}