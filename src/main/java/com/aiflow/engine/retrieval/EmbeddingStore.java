package com.aiflow.engine.retrieval;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 切片向量的存储抽象：PG 实现写 pgvector 列（持久化），内存实现用于测试与离线降级。
 * 只负责向量本身；切片正文与元数据在 document_chunks 表（JPA）。
 *
 * <p>读写都带 provider 标识：向量只在同一个模型的向量空间里可比。
 * 换 embedding provider 之后，旧向量必须重建索引，而不是继续参与相似度计算 ——
 * 否则两个空间的余弦会得出「看起来正常」的分数，是静默错误。
 */
public interface EmbeddingStore {

    /** 无法判定来源的历史向量（升级前入库的数据）。它不参与检索，直到重建索引。 */
    String UNKNOWN_MODEL = "(unknown)";

    String id();

    boolean persistent();

    /** 写向量，并同时记录它由哪个 provider 生成：两者必须在同一次写里落地，不能分开写。 */
    void upsertAll(String kbId, String embeddingModel, Map<String, float[]> embeddings);

    /** 只取该 provider 生成的向量；其他模型的历史向量不参与相似度计算。 */
    Map<String, float[]> load(String kbId, String embeddingModel);

    /** 该 kb 下已有哪些 provider 的向量（provider -> 切片数）：用于发现「索引是别的模型建的」。 */
    Map<String, Long> indexedModels(String kbId);

    void removeAll(Collection<String> chunkIds);

    /** 是否支持 ANN 近邻查询：pgvector 支持；内存实现不支持，走精确余弦。 */
    default boolean supportsAnn() {
        return false;
    }

    /**
     * ANN 近邻查询：按余弦距离升序取前 {@code limit} 个切片，附带向量（MMR 要用它算冗余度）。
     *
     * <p>不支持 ANN 的实现返回 {@code null}，调用方据此回退精确路径 —— 空列表的含义完全不同（查了，但没命中）。
     * 这个区分必须有：把「不支持」当成「没结果」会让检索静默返回空。
     */
    default List<AnnHit> searchSimilar(String kbId, String embeddingModel, float[] query, int limit) {
        return null;
    }

    /** 一次 ANN 命中：切片 id + 余弦相似度（与精确路径同口径，[-1,1]）+ 向量。 */
    record AnnHit(String chunkId, double cosine, float[] embedding) {
    }
}