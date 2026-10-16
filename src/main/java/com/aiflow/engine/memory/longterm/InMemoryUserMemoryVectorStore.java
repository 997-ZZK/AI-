package com.aiflow.engine.memory.longterm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储：测试（H2 不装 pgvector）与「不带数据库也要能跑通长期记忆」的离线档。
 *
 * <p>刻意**不支持 ANN**：内存实现的全量余弦已经是精确路径，声明支持 ANN 只会掩盖真实链路上的差异。
 * 于是单测覆盖的是精确路径，ANN 路径由 PG 上的真实栈与 {@code PgVectorUserMemoryVectorStore} 的用例覆盖。
 */
@Component
@ConditionalOnProperty(name = "aiflow.retrieval.embedding-store", havingValue = "memory")
public class InMemoryUserMemoryVectorStore implements UserMemoryVectorStore {

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "memory";
    }

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public void upsert(UUID userId, UUID memoryId, String embeddingModel, float[] vector) {
        if (memoryId == null) {
            return;
        }
        entries.put(memoryId, new Entry(userId, embeddingModel, vector == null ? null : vector.clone()));
    }

    @Override
    public Map<UUID, float[]> load(UUID userId, String embeddingModel) {
        Map<UUID, float[]> vectors = new LinkedHashMap<>();
        entries.forEach((id, entry) -> {
            if (usable(entry, userId, embeddingModel)) {
                vectors.put(id, entry.vector());
            }
        });
        return vectors;
    }

    @Override
    public void remove(UUID memoryId) {
        entries.remove(memoryId);
    }

    @Override
    public Map<String, Long> indexedModels(UUID userId) {
        Map<String, Long> models = new LinkedHashMap<>();
        entries.forEach((id, entry) -> {
            if (userId == null || userId.equals(entry.userId())) {
                models.merge(entry.embeddingModel() == null ? "(unknown)" : entry.embeddingModel(), 1L, Long::sum);
            }
        });
        return models;
    }

    private static boolean usable(Entry entry, UUID userId, String embeddingModel) {
        if (entry.vector() == null || embeddingModel == null || !embeddingModel.equals(entry.embeddingModel())) {
            return false;
        }
        return userId == null || userId.equals(entry.userId());
    }

    private record Entry(UUID userId, String embeddingModel, float[] vector) {
    }
}
