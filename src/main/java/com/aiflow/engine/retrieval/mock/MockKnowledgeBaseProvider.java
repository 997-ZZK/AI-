package com.aiflow.engine.retrieval.mock;

import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.RetrievalProvider;
import com.aiflow.engine.retrieval.RetrievalQuery;
import com.aiflow.engine.retrieval.RetrievedChunk;
import com.aiflow.engine.retrieval.Vectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 云端知识库的 mock 实现：在内存语料上按余弦召回，但把分数放大到「厂商量纲」（默认 0..100），
 * 用来验证归一化适配层与本地检索在同一条阈值/裁剪链上的行为一致性。
 *
 * 契约与真实 HTTP 适配器一致：返回 rawScore 不做归一化（归一化是 ScoreNormalizer 的职责）。
 */
public class MockKnowledgeBaseProvider implements RetrievalProvider {

    private final String source;
    private final Function<String, List<RetrievedChunk>> corpusLoader;
    private final EmbeddingProvider embeddingProvider;
    private final double rawScale;
    private final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();

    public MockKnowledgeBaseProvider(String source, Function<String, List<RetrievedChunk>> corpusLoader,
                                     EmbeddingProvider embeddingProvider, double rawScale) {
        this.source = source;
        this.corpusLoader = corpusLoader;
        this.embeddingProvider = embeddingProvider;
        this.rawScale = rawScale;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        List<RetrievedChunk> knowledge = corpusLoader.apply(query.kbId());
        if (query.embedding() == null || knowledge.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> scored = new ArrayList<>(knowledge.size());
        for (RetrievedChunk item : knowledge) {
            float[] itemEmbedding = embeddingCache.computeIfAbsent(item.chunkId(),
                    ignored -> embeddingProvider.embed(item.text()));
            double cosine = Vectors.cosine(query.embedding(), itemEmbedding);
            scored.add(item.withEmbedding(itemEmbedding).withScore(0, cosine * rawScale));
        }
        scored.sort(Comparator.comparingDouble(RetrievedChunk::rawScore).reversed()
                .thenComparing(RetrievedChunk::chunkId));
        return scored.size() > query.topK() ? List.copyOf(scored.subList(0, query.topK())) : List.copyOf(scored);
    }
}
