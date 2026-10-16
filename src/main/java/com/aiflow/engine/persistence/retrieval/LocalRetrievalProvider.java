package com.aiflow.engine.persistence.retrieval;

import com.aiflow.engine.persistence.entity.DocumentChunkEntity;
import com.aiflow.engine.persistence.repo.DocumentChunkRepository;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.EmbeddingStore;
import com.aiflow.engine.retrieval.RetrievalException;
import com.aiflow.engine.retrieval.RetrievalProvider;
import com.aiflow.engine.retrieval.RetrievalQuery;
import com.aiflow.engine.retrieval.RetrievedChunk;
import com.aiflow.engine.retrieval.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 本地检索：kb 级候选集上做余弦（1024 维）。rawScore 是原始余弦（[-1,1]），
 * 由 CosineScoreNormalizer 归一化后再进统一阈值/裁剪链。
 *
 * <p>候选集有两条来源：存储支持 ANN 时走 {@code embedding <=> ?}（HNSW 索引，kb 大时不把全量向量搬进 JVM），
 * 否则把整库向量读进来逐条算精确余弦。两条路径产出同一份 {@link RetrievedChunk}，阈值 / 断崖 / MMR
 * 一行都不用改 —— 它们只认分数，不关心分数是算出来的还是查出来的。
 *
 * <p>ANN 是近似的，它只保证「大概率」找回最近的 N 条，因此：
 * <ul>
 *   <li>候选宽度必须留余量（{@code ann.candidates} ≥ {@code maxK}），否则断崖 top-k 没有足够候选可挑；</li>
 *   <li>ANN 抛异常由存储层自己重试（见 PgVectorEmbeddingStore），这里只处理「返回了但结果为空」；</li>
 *   <li><b>ANN 查空一律回退精确路径</b>：带 where 过滤的 HNSW 在 pgvector 0.8 之前是后过滤
 *       （扫满 ef_search 条再筛 kb/model），筛完可能是空的 —— 那不代表真的没命中。空结果退回精确路径，
 *       要么拿到真实命中，要么走「索引是别的模型建的」那条显式报错，语义与纯精确路径完全一致。</li>
 * </ul>
 */
public class LocalRetrievalProvider implements RetrievalProvider {

    public static final String SOURCE = "local";

    private static final Logger log = LoggerFactory.getLogger(LocalRetrievalProvider.class);

    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingStore embeddingStore;
    private final EmbeddingProvider embeddingProvider;
    private final boolean annEnabled;
    private final int annCandidates;

    public LocalRetrievalProvider(DocumentChunkRepository chunkRepository, EmbeddingStore embeddingStore,
                                  EmbeddingProvider embeddingProvider) {
        this(chunkRepository, embeddingStore, embeddingProvider, false, 0);
    }

    public LocalRetrievalProvider(DocumentChunkRepository chunkRepository, EmbeddingStore embeddingStore,
                                  EmbeddingProvider embeddingProvider, boolean annEnabled, int annCandidates) {
        this.chunkRepository = chunkRepository;
        this.embeddingStore = embeddingStore;
        this.embeddingProvider = embeddingProvider;
        this.annEnabled = annEnabled;
        this.annCandidates = Math.max(0, annCandidates);
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        if (query.embedding() == null) {
            throw new RetrievalException("本地检索需要查询向量");
        }
        String modelId = embeddingProvider.id();
        if (annEnabled && embeddingStore.supportsAnn()) {
            // 候选宽度取 candidates 与 topK 的较大者：candidates 配小了也不至于连 topK 都凑不齐
            int limit = Math.max(annCandidates, query.topK());
            List<EmbeddingStore.AnnHit> hits = embeddingStore.searchSimilar(query.kbId(), modelId,
                    query.embedding(), limit);
            if (hits != null && !hits.isEmpty()) {
                return trim(fromAnn(hits), query.topK());
            }
            if (hits != null) {
                log.debug("ANN 查询 kb={} model={} 返回 0 条，回退精确路径（过滤型 HNSW 的后过滤可能扫空）",
                        query.kbId(), modelId);
            }
        }
        return exact(query, modelId);
    }

    /** ANN 命中回表组装：向量与分数来自命中本身，正文与元数据来自 document_chunks。 */
    private List<RetrievedChunk> fromAnn(List<EmbeddingStore.AnnHit> hits) {
        Map<String, EmbeddingStore.AnnHit> byChunkId = new LinkedHashMap<>();
        for (EmbeddingStore.AnnHit hit : hits) {
            byChunkId.put(hit.chunkId(), hit);
        }
        List<UUID> ids = new ArrayList<>(byChunkId.size());
        for (String chunkId : byChunkId.keySet()) {
            ids.add(UUID.fromString(chunkId));
        }
        List<RetrievedChunk> scored = new ArrayList<>(hits.size());
        for (DocumentChunkEntity chunk : chunkRepository.findAllByIdWithDocument(ids)) {
            EmbeddingStore.AnnHit hit = byChunkId.get(chunk.getId().toString());
            if (hit == null) {
                continue;
            }
            scored.add(toChunk(chunk, hit.cosine(), hit.embedding()));
        }
        if (scored.size() < hits.size()) {
            // 命中后、回表前切片被删了：少召回一条，不值得让整次检索失败
            log.debug("ANN 命中 {} 条，回表取到 {} 条（其余切片已被删除）", hits.size(), scored.size());
        }
        sortByScore(scored);
        return scored;
    }

    /** 精确路径：把该 provider 的全部向量读进内存逐条算余弦。小 kb 的默认路径，也是 ANN 的兜底与对照。 */
    private List<RetrievedChunk> exact(RetrievalQuery query, String modelId) {
        Map<String, float[]> vectors = embeddingStore.load(query.kbId(), modelId);
        if (vectors.isEmpty()) {
            Map<String, Long> indexed = embeddingStore.indexedModels(query.kbId());
            if (!indexed.isEmpty()) {
                // 索引是别的模型建的：这里必须显式报错。两个向量空间的余弦算得出分数，但那个分数没有意义，
                // 静默返回空结果会让排查方向跑到「文档里没有这条内容」上去，那是错的。
                throw RetrievalException.safe("知识库 " + query.kbId() + " 的向量由 " + indexed.keySet()
                        + " 生成，与当前 embedding provider " + modelId + " 不在同一向量空间；"
                        + "请先重建索引：POST /api/v1/kbs/" + query.kbId() + "/reindex");
            }
            return List.of();
        }
        List<RetrievedChunk> scored = new ArrayList<>(vectors.size());
        for (DocumentChunkEntity chunk : chunkRepository.findByKbIdWithDocument(query.kbId())) {
            float[] vector = vectors.get(chunk.getId().toString());
            if (vector == null) {
                continue;
            }
            scored.add(toChunk(chunk, Vectors.cosine(query.embedding(), vector), vector));
        }
        sortByScore(scored);
        return trim(scored, query.topK());
    }

    private static RetrievedChunk toChunk(DocumentChunkEntity chunk, double cosine, float[] vector) {
        return new RetrievedChunk(SOURCE,
                chunk.getDocument().getId().toString(),
                chunk.getDocument().getTitle(),
                chunk.getId().toString(),
                chunk.getChunkIndex(),
                chunk.getSectionPath(),
                chunk.getPageFrom(),
                chunk.getPageTo(),
                chunk.getContent(),
                chunk.getContentHash(),
                cosine,
                cosine,
                vector);
    }

    private static void sortByScore(List<RetrievedChunk> scored) {
        scored.sort(Comparator.comparingDouble(RetrievedChunk::rawScore).reversed()
                .thenComparing(RetrievedChunk::chunkId));
    }

    private static List<RetrievedChunk> trim(List<RetrievedChunk> scored, int topK) {
        return scored.size() > topK ? List.copyOf(scored.subList(0, topK)) : List.copyOf(scored);
    }
}
