package com.aiflow.engine.persistence.retrieval;

import com.aiflow.engine.ingest.ChunkDraft;
import com.aiflow.engine.ingest.DocumentSourceType;
import com.aiflow.engine.persistence.entity.DocumentChunkEntity;
import com.aiflow.engine.persistence.entity.DocumentEntity;
import com.aiflow.engine.persistence.repo.DocumentChunkRepository;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.EmbeddingStore;
import com.aiflow.engine.retrieval.RetrievalException;
import com.aiflow.engine.retrieval.RetrievalQuery;
import com.aiflow.engine.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地检索的候选集来源选择：ANN 优先、精确兜底。
 *
 * <p>这几条守的是「换了候选集来源，但不能换语义」：
 * <ul>
 *   <li>ANN 命中时不碰 load()：否则「优化」退化成既查索引又搬全量向量，比不改还慢；</li>
 *   <li>ANN 空结果必须回退精确路径：带 where 过滤的 HNSW 在 pgvector 0.8 之前是后过滤，筛空是常态；</li>
 *   <li>「索引是别的模型建的」那条显式报错在两条路径上都要能抛出来；</li>
 *   <li>ann.enabled=false 或存储不支持 ANN 时，一次都不许碰 ANN 接口。</li>
 * </ul>
 */
class LocalRetrievalProviderAnnTest {

    private static final String KB = "kb-ann";
    private static final int DIMENSIONS = 1024;

    private final DocumentChunkRepository repository = mock(DocumentChunkRepository.class);
    private final FixedEmbeddingProvider embeddingProvider = new FixedEmbeddingProvider("fake-embedding");

    @Test
    void annHitsAreUsedWithoutLoadingTheWholeKnowledgeBase() {
        DocumentChunkEntity first = chunk("报销制度.md", 0, "发票要求：增值税专用发票");
        DocumentChunkEntity second = chunk("差旅制度.md", 1, "住宿标准：一线城市 600 元");
        when(repository.findAllByIdWithDocument(any())).thenReturn(List.of(first, second));
        float[] vector = embeddingProvider.vector();
        RecordingStore store = RecordingStore.ann(List.of(
                new EmbeddingStore.AnnHit(second.getId().toString(), 0.72, vector),
                new EmbeddingStore.AnnHit(first.getId().toString(), 0.91, vector)));

        List<RetrievedChunk> hits = provider(store, true, 48).retrieve(query(6));

        assertThat(hits).extracting(RetrievedChunk::chunkId)
                .containsExactly(first.getId().toString(), second.getId().toString());
        assertThat(hits).extracting(RetrievedChunk::rawScore).containsExactly(0.91, 0.72);
        assertThat(hits).allSatisfy(hit -> assertThat(hit.source()).isEqualTo(LocalRetrievalProvider.SOURCE));
        assertThat(hits.get(0).docTitle()).isEqualTo("报销制度.md");
        assertThat(hits.get(0).text()).isEqualTo("发票要求：增值税专用发票");
        assertThat(store.calls).containsExactly("searchSimilar");
        verify(repository, never()).findByKbIdWithDocument(any());
    }

    @Test
    void annCandidateWidthIsWidenedToAtLeastTopK() {
        when(repository.findAllByIdWithDocument(any())).thenReturn(List.of());
        RecordingStore store = RecordingStore.ann(List.of());

        provider(store, true, 2).retrieve(query(6));

        // candidates 配得比 topK 还小是配置错误：不加宽的话断崖 top-k 连候选都没有
        assertThat(store.lastLimit).isEqualTo(6);
    }

    @Test
    void emptyAnnResultFallsBackToExactBecauseFilteredHnswCanScanEmpty() {
        DocumentChunkEntity only = chunk("报销制度.md", 0, "发票要求");
        when(repository.findAllByIdWithDocument(any())).thenReturn(List.of());
        when(repository.findByKbIdWithDocument(KB)).thenReturn(List.of(only));
        Map<String, float[]> vectors = new LinkedHashMap<>();
        vectors.put(only.getId().toString(), embeddingProvider.vector());
        RecordingStore store = RecordingStore.ann(List.of()).withExact(vectors, Map.of("fake-embedding", 1L));

        List<RetrievedChunk> hits = provider(store, true, 48).retrieve(query(6));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).rawScore()).isEqualTo(1.0);
        assertThat(store.calls).containsExactly("searchSimilar", "load");
    }

    @Test
    void crossModelIndexStillRaisesTheExplicitErrorThroughTheFallbackPath() {
        when(repository.findByKbIdWithDocument(KB)).thenReturn(List.of());
        RecordingStore store = RecordingStore.ann(List.of()).withExact(Map.of(), Map.of("bge-m3", 12L));

        assertThatThrownBy(() -> provider(store, true, 48).retrieve(query(6)))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("不在同一向量空间")
                .hasMessageContaining("/reindex");
    }

    @Test
    void annDisabledNeverTouchesTheAnnPath() {
        DocumentChunkEntity only = chunk("报销制度.md", 0, "发票要求");
        when(repository.findByKbIdWithDocument(KB)).thenReturn(List.of(only));
        Map<String, float[]> vectors = new LinkedHashMap<>();
        vectors.put(only.getId().toString(), embeddingProvider.vector());
        RecordingStore store = RecordingStore.ann(List.of()).withExact(vectors, Map.of("fake-embedding", 1L));

        List<RetrievedChunk> hits = provider(store, false, 48).retrieve(query(6));

        assertThat(hits).hasSize(1);
        assertThat(store.calls).containsExactly("load");
    }

    @Test
    void storeWithoutAnnSupportStaysOnTheExactPath() {
        DocumentChunkEntity only = chunk("报销制度.md", 0, "发票要求");
        when(repository.findByKbIdWithDocument(KB)).thenReturn(List.of(only));
        Map<String, float[]> vectors = new LinkedHashMap<>();
        vectors.put(only.getId().toString(), embeddingProvider.vector());
        RecordingStore store = RecordingStore.exactOnly(vectors, Map.of("fake-embedding", 1L));

        List<RetrievedChunk> hits = provider(store, true, 48).retrieve(query(6));

        assertThat(hits).hasSize(1);
        assertThat(store.calls).containsExactly("load");
    }

    @Test
    void annHitMissingFromTheDatabaseIsSkippedInsteadOfFailingTheRetrieval() {
        DocumentChunkEntity kept = chunk("报销制度.md", 0, "发票要求");
        when(repository.findAllByIdWithDocument(any())).thenReturn(List.of(kept));
        RecordingStore store = RecordingStore.ann(List.of(
                new EmbeddingStore.AnnHit(UUID.randomUUID().toString(), 0.99, embeddingProvider.vector()),
                new EmbeddingStore.AnnHit(kept.getId().toString(), 0.88, embeddingProvider.vector())));

        List<RetrievedChunk> hits = provider(store, true, 48).retrieve(query(6));

        assertThat(hits).extracting(RetrievedChunk::chunkId).containsExactly(kept.getId().toString());
    }

    @Test
    void annResultsAreTrimmedToTopK() {
        List<DocumentChunkEntity> chunks = new ArrayList<>();
        List<EmbeddingStore.AnnHit> annHits = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            DocumentChunkEntity entity = chunk("文档" + index + ".md", index, "内容" + index);
            chunks.add(entity);
            annHits.add(new EmbeddingStore.AnnHit(entity.getId().toString(), 0.5 + index * 0.05,
                    embeddingProvider.vector()));
        }
        when(repository.findAllByIdWithDocument(any())).thenReturn(chunks);
        RecordingStore store = RecordingStore.ann(annHits);

        List<RetrievedChunk> hits = provider(store, true, 48).retrieve(query(3));

        assertThat(hits).hasSize(3);
        assertThat(hits).extracting(RetrievedChunk::rawScore).containsExactly(0.70, 0.65, 0.60);
    }

    private LocalRetrievalProvider provider(RecordingStore store, boolean annEnabled, int annCandidates) {
        return new LocalRetrievalProvider(repository, store, embeddingProvider, annEnabled, annCandidates);
    }

    private RetrievalQuery query(int topK) {
        return new RetrievalQuery("发票要求", embeddingProvider.vector(), KB, topK, 0.1, Map.of());
    }

    private static DocumentChunkEntity chunk(String title, int index, String content) {
        DocumentEntity document = new DocumentEntity(UUID.randomUUID(), KB, title, DocumentSourceType.MARKDOWN,
                null, "doc-hash-" + title, null, Instant.now());
        return new DocumentChunkEntity(UUID.randomUUID(), document, KB,
                ChunkDraft.of(index, "章节" + index, null, null, content), Instant.now());
    }

    /** 固定向量：ANN 用例里分数由假命中直接给定，向量只用来验证原文与元数据的组装。 */
    private static final class FixedEmbeddingProvider implements EmbeddingProvider {

        private final String id;
        private final float[] vector;

        private FixedEmbeddingProvider(String id) {
            this.id = id;
            this.vector = new float[DIMENSIONS];
            this.vector[0] = 1f;
        }

        private float[] vector() {
            return vector.clone();
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            return texts.stream().map(ignored -> vector.clone()).toList();
        }
    }

    /** 记录调用轨迹的假存储：calls 里出现/不出现哪个方法，就是用例要钉死的行为。 */
    private static final class RecordingStore implements EmbeddingStore {

        private final List<String> calls = new ArrayList<>();
        private final boolean annSupported;
        private final List<EmbeddingStore.AnnHit> annHits;
        private Map<String, float[]> vectors = Map.of();
        private Map<String, Long> models = Map.of();
        private Integer lastLimit;

        private RecordingStore(boolean annSupported, List<EmbeddingStore.AnnHit> annHits) {
            this.annSupported = annSupported;
            this.annHits = annHits;
        }

        private static RecordingStore ann(List<EmbeddingStore.AnnHit> hits) {
            return new RecordingStore(true, hits);
        }

        private static RecordingStore exactOnly(Map<String, float[]> vectors, Map<String, Long> models) {
            return new RecordingStore(false, null).withExact(vectors, models);
        }

        private RecordingStore withExact(Map<String, float[]> vectors, Map<String, Long> models) {
            this.vectors = vectors;
            this.models = models;
            return this;
        }

        @Override
        public String id() {
            return "recording";
        }

        @Override
        public boolean persistent() {
            return false;
        }

        @Override
        public void upsertAll(String kbId, String embeddingModel, Map<String, float[]> embeddings) {
            throw new UnsupportedOperationException("检索用例不写向量");
        }

        @Override
        public Map<String, float[]> load(String kbId, String embeddingModel) {
            calls.add("load");
            return vectors;
        }

        @Override
        public Map<String, Long> indexedModels(String kbId) {
            calls.add("indexedModels");
            return models;
        }

        @Override
        public void removeAll(Collection<String> chunkIds) {
            throw new UnsupportedOperationException("检索用例不删向量");
        }

        @Override
        public boolean supportsAnn() {
            return annSupported;
        }

        @Override
        public List<EmbeddingStore.AnnHit> searchSimilar(String kbId, String embeddingModel, float[] query, int limit) {
            if (!annSupported) {
                throw new AssertionError("不支持 ANN 的存储不应被调用 searchSimilar");
            }
            calls.add("searchSimilar");
            lastLimit = limit;
            return annHits;
        }
    }
}
