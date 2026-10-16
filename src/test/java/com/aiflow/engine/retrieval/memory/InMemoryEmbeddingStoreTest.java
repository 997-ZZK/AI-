package com.aiflow.engine.retrieval.memory;

import com.aiflow.engine.retrieval.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存向量存储的模型隔离：向量只在同一个 provider 的向量空间里可比，
 * 因此 upsert / load 都必须带模型标识，不能只按 kb 混在一起。
 */
class InMemoryEmbeddingStoreTest {

    private final InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();

    private static float[] vector(float value) {
        float[] vector = new float[3];
        Arrays.fill(vector, value);
        return vector;
    }

    @Test
    void loadOnlyReturnsVectorsOfTheRequestedModel() {
        store.upsertAll("kb-1", "bge-m3", Map.of("a", vector(1f)));
        store.upsertAll("kb-1", "mock-embedding", Map.of("b", vector(2f)));

        assertThat(store.load("kb-1", "bge-m3")).containsOnlyKeys("a");
        assertThat(store.load("kb-1", "mock-embedding")).containsOnlyKeys("b");
        assertThat(store.load("kb-1", "another-model")).isEmpty();
        assertThat(store.load("kb-2", "bge-m3")).isEmpty();
    }

    @Test
    void indexedModelsListsEveryModelWithVectorCounts() {
        store.upsertAll("kb-1", "bge-m3", Map.of("a", vector(1f), "b", vector(1f)));
        store.upsertAll("kb-1", "mock-embedding", Map.of("c", vector(1f)));

        assertThat(store.indexedModels("kb-1")).containsExactlyInAnyOrderEntriesOf(
                Map.of("bge-m3", 2L, "mock-embedding", 1L));
        assertThat(store.indexedModels("kb-2")).isEmpty();
    }

    /** 升级前的历史向量不知道来源，统一记到 (unknown) 名下，而不是冒充当前模型。 */
    @Test
    void nullModelIsRecordedAsUnknownRatherThanCurrentModel() {
        store.upsertAll("kb-1", null, Map.of("a", vector(1f)));

        assertThat(store.load("kb-1", EmbeddingStore.UNKNOWN_MODEL)).containsOnlyKeys("a");
        assertThat(store.load("kb-1", "bge-m3")).isEmpty();
        assertThat(store.indexedModels("kb-1")).containsOnlyKeys(EmbeddingStore.UNKNOWN_MODEL);
    }

    @Test
    void removeAllDropsChunkIdsAcrossModels() {
        store.upsertAll("kb-1", "bge-m3", Map.of("a", vector(1f), "b", vector(1f)));
        store.upsertAll("kb-1", "mock-embedding", Map.of("a", vector(1f)));

        store.removeAll(List.of("a"));

        assertThat(store.load("kb-1", "bge-m3")).containsOnlyKeys("b");
        assertThat(store.load("kb-1", "mock-embedding")).isEmpty();
    }
}