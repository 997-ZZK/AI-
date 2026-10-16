package com.aiflow.engine.persistence.retrieval;

import com.aiflow.engine.persistence.entity.DocumentChunkEntity;
import com.aiflow.engine.persistence.repo.DocumentChunkRepository;
import com.aiflow.engine.retrieval.RetrievedChunk;

import java.util.List;
import java.util.function.Function;

/**
 * 把库里的切片当作「云端知识库」的语料，供 mock 云端 provider 使用：
 * 这样离线环境也能验证「本地 + 云端」双来源融合、统一阈值与去重行为。
 */
public class JpaKnowledgeBaseCorpus implements Function<String, List<RetrievedChunk>> {

    private final DocumentChunkRepository chunkRepository;
    private final String source;

    public JpaKnowledgeBaseCorpus(DocumentChunkRepository chunkRepository, String source) {
        this.chunkRepository = chunkRepository;
        this.source = source;
    }

    @Override
    public List<RetrievedChunk> apply(String kbId) {
        return chunkRepository.findByKbIdWithDocument(kbId).stream()
                .map(chunk -> new RetrievedChunk(source,
                        chunk.getDocument().getId().toString(),
                        chunk.getDocument().getTitle(),
                        chunk.getId().toString(),
                        chunk.getChunkIndex(),
                        chunk.getSectionPath(),
                        chunk.getPageFrom(),
                        chunk.getPageTo(),
                        chunk.getContent(),
                        chunk.getContentHash(),
                        0,
                        0,
                        null))
                .toList();
    }
}
