package com.aiflow.engine.persistence.retrieval;

import com.aiflow.engine.memory.longterm.UserMemoryVectorStore;
import com.aiflow.engine.retrieval.Vectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link UserMemoryVectorStore} 的 pgvector 实现（只碰 user_memories 的向量列，其余字段归 JPA 实体管）。
 *
 * <p>与 {@code PgVectorEmbeddingStore} 的差别：不设 HNSW 查询期参数（ef_search / iterative_scan）。
 * 理由很具体 —— 长期记忆的条目上限是「单用户 200 条」（{@code max-entries-per-user}），量级远小于知识库切片，
 * 而带 {@code user_id} 过滤的 ANN 查询在候选被筛空时，本类上层的召回服务会**回退精确路径**兜住召回率。
 * 少一层查询期调优代码，换来的是这条链上少一个能配置错的旋钮。
 */
@Component
@ConditionalOnProperty(name = "aiflow.retrieval.embedding-store", havingValue = "pgvector", matchIfMissing = true)
public class PgVectorUserMemoryVectorStore implements UserMemoryVectorStore {

    /** 向量与模型标记必须在同一条 SQL 里写：分开写会出现「标了模型 A、向量还是模型 B」的中间态。 */
    private static final String UPSERT_SQL =
            "update user_memories set embedding = cast(? as vector), embedding_model = ? where id = cast(? as uuid)";
    private static final String LOAD_SQL =
            "select id::text as memory_id, embedding::text as embedding from user_memories "
                    + "where user_id = cast(? as uuid) and embedding_model = ? and embedding is not null";
    private static final String ANN_SQL =
            "select id::text as memory_id, embedding::text as embedding, "
                    + "1 - (embedding <=> cast(? as vector)) as cosine from user_memories "
                    + "where user_id = cast(? as uuid) and embedding_model = ? and embedding is not null "
                    + "order by embedding <=> cast(? as vector) limit ?";
    private static final String MODELS_SQL =
            "select coalesce(embedding_model, '(unknown)') as model, count(*) as memory_count from user_memories "
                    + "where user_id = cast(? as uuid) and embedding is not null group by 1";
    private static final String CLEAR_SQL =
            "update user_memories set embedding = null, embedding_model = null where id = cast(? as uuid)";

    private final JdbcTemplate jdbcTemplate;

    public PgVectorUserMemoryVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String id() {
        return "pgvector";
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public boolean supportsAnn() {
        return true;
    }

    @Override
    public void upsert(UUID userId, UUID memoryId, String embeddingModel, float[] vector) {
        if (memoryId == null || vector == null) {
            return;
        }
        jdbcTemplate.update(UPSERT_SQL, Vectors.toLiteral(vector), embeddingModel, memoryId.toString());
    }

    @Override
    public Map<UUID, float[]> load(UUID userId, String embeddingModel) {
        return jdbcTemplate.query(LOAD_SQL, resultSet -> {
            Map<UUID, float[]> vectors = new LinkedHashMap<>();
            while (resultSet.next()) {
                float[] vector = Vectors.parse(resultSet.getString("embedding"));
                if (vector != null) {
                    vectors.put(UUID.fromString(resultSet.getString("memory_id")), vector);
                }
            }
            return vectors;
        }, userId.toString(), embeddingModel);
    }

    @Override
    public List<Hit> search(UUID userId, String embeddingModel, float[] query, int limit) {
        if (query == null || query.length == 0 || limit <= 0) {
            return null;
        }
        String literal = Vectors.toLiteral(query);
        return jdbcTemplate.query(ANN_SQL, resultSet -> {
            List<Hit> hits = new ArrayList<>();
            while (resultSet.next()) {
                hits.add(new Hit(UUID.fromString(resultSet.getString("memory_id")), resultSet.getDouble("cosine"),
                        Vectors.parse(resultSet.getString("embedding"))));
            }
            return hits;
        }, literal, userId.toString(), embeddingModel, literal, limit);
    }

    @Override
    public void remove(UUID memoryId) {
        if (memoryId != null) {
            jdbcTemplate.update(CLEAR_SQL, memoryId.toString());
        }
    }

    @Override
    public Map<String, Long> indexedModels(UUID userId) {
        return jdbcTemplate.query(MODELS_SQL, resultSet -> {
            Map<String, Long> models = new LinkedHashMap<>();
            while (resultSet.next()) {
                models.put(resultSet.getString("model"), resultSet.getLong("memory_count"));
            }
            return models;
        }, userId.toString());
    }
}
