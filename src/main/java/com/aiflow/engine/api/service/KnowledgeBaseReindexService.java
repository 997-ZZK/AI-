package com.aiflow.engine.api.service;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.persistence.entity.DocumentChunkEntity;
import com.aiflow.engine.persistence.entity.DocumentEntity;
import com.aiflow.engine.persistence.repo.DocumentChunkRepository;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.EmbeddingStore;
import com.aiflow.engine.retrieval.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引重建：把某个 kb 下所有切片的向量用**当前** embedding provider 重算，并同步模型标记。
 *
 * <p>什么时候必须做：换 embedding provider / 换模型之后。向量只在同一个模型的向量空间里可比，
 * 换了模型还拿旧向量去算余弦，得到的是「看起来正常但毫无意义」的分数。
 *
 * <p>重建用切片自己的 content，不重新解析文档：chunkId 与切片边界保持不变，
 * 已经写进对话历史或引用里的 chunkId 不会失效。代价是切片策略变更后需要「删文档重新入库」而不是 reindex。
 */
@Service
public class KnowledgeBaseReindexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseReindexService.class);

    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingStore embeddingStore;

    public KnowledgeBaseReindexService(DocumentChunkRepository chunkRepository, EmbeddingProvider embeddingProvider,
                                       EmbeddingStore embeddingStore) {
        this.chunkRepository = chunkRepository;
        this.embeddingProvider = embeddingProvider;
        this.embeddingStore = embeddingStore;
    }

    public ReindexResult reindex(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "kbId 不能为空");
        }
        String safeKbId = kbId.strip();
        Map<String, Long> previous = embeddingStore.indexedModels(safeKbId);
        List<DocumentChunkEntity> chunks = chunkRepository.findByKbIdWithDocument(safeKbId);
        if (chunks.isEmpty()) {
            log.info("reindex skipped kb={} reason=no chunks", safeKbId);
            return new ReindexResult(safeKbId, 0, 0, embeddingProvider.id(), previous);
        }
        List<float[]> vectors = embeddingProvider.embedAll(
                chunks.stream().map(DocumentChunkEntity::getContent).toList());
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException("embedding 返回条数与切片数不一致: "
                    + vectors.size() + " != " + chunks.size());
        }
        Map<String, float[]> embeddings = new LinkedHashMap<>();
        for (int index = 0; index < chunks.size(); index++) {
            float[] vector = vectors.get(index);
            Vectors.requireDimensions(vector, embeddingProvider.dimensions(),
                    "embedding provider " + embeddingProvider.id());
            embeddings.put(chunks.get(index).getId().toString(), vector);
        }
        embeddingStore.upsertAll(safeKbId, embeddingProvider.id(), embeddings);
        long documents = chunks.stream().map(DocumentChunkEntity::getDocument).map(DocumentEntity::getId)
                .distinct().count();
        log.info("reindexed kb={} documents={} chunks={} embedding={} previous={}", safeKbId, documents,
                chunks.size(), embeddingProvider.id(), previous);
        return new ReindexResult(safeKbId, documents, chunks.size(), embeddingProvider.id(), previous);
    }

    /** previousModels 让调用方看清「重建前这些向量是谁生成的」，为空的旧向量记作 (unknown)。 */
    public record ReindexResult(String kbId, long documents, int chunks, String embeddingModel,
                                Map<String, Long> previousModels) {
    }
}