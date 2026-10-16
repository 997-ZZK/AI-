package com.aiflow.engine.memory.longterm;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 长期记忆条目向量的存储抽象（与知识库的 {@code EmbeddingStore} 分开：那边是 kb + 模型两级隔离、只认 document_chunks）。
 *
 * <p>读写都带 {@code embeddingModel}：向量只在同一个模型的向量空间里可比。换 embedding 链之后旧向量必须重建，
 * 而不是继续参与相似度计算 —— 否则两个空间的余弦会得出「看起来正常」的分数，属于静默错误。
 *
 * <p>{@link #search} 返回 {@code null} 表示「存储不支持 ANN」（调用方回退精确余弦）；空列表的含义是「查了，但没命中」。
 * 这个区分必须有：把「不支持」当成「没结果」会让召回静默返回空。
 *
 * <p>写路径带 {@code userId}：PG 实现用不着它（作用域在行上），内存实现要靠它按用户过滤 ——
 * 与其让内存实现假装不需要，不如把过滤条件显式写在契约里。
 */
public interface UserMemoryVectorStore {

    String id();

    boolean persistent();

    default boolean supportsAnn() {
        return false;
    }

    /** 写向量与模型标记（必须是同一次写：分开写会出现「标了模型 A、向量还是模型 B」的中间态）。 */
    void upsert(UUID userId, UUID memoryId, String embeddingModel, float[] vector);

    /** 取该用户由该模型生成的**全部**向量（精确路径与「ANN 未命中」的回退都走它）。 */
    Map<UUID, float[]> load(UUID userId, String embeddingModel);

    /** ANN 近邻查询；不支持时返回 {@code null}。 */
    default List<Hit> search(UUID userId, String embeddingModel, float[] query, int limit) {
        return null;
    }

    void remove(UUID memoryId);

    /** 该用户已有哪些模型的向量（模型 -> 条数）：用于发现「索引是别的模型建的」并给出显式提示。 */
    Map<String, Long> indexedModels(UUID userId);

    record Hit(UUID memoryId, double cosine, float[] vector) {
    }
}
