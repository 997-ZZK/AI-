package com.aiflow.engine.retrieval.post;

import com.aiflow.engine.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdjacentMergerTest {
    @Test
    void mergesKnownAdjacentChunksOnlyWithinOneSourceAndDocument() {
        var merged = AdjacentMerger.apply(List.of(chunk("cloud", "same", "a", 0), chunk("cloud", "same", "b", 1)), 100);
        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().text()).isEqualTo("a\nb");
    }

    @Test
    void sameDocumentIdAcrossSourcesDoesNotMerge() {
        var merged = AdjacentMerger.apply(List.of(chunk("local", "same", "a", 0), chunk("cloud", "same", "b", 1)), 100);
        assertThat(merged).hasSize(2);
    }

    @Test
    void unknownOrderNeverMergesWithZeroOrAnotherUnknownHit() {
        var merged = AdjacentMerger.apply(List.of(chunk("cloud", "same", "a", -1), chunk("cloud", "same", "b", 0),
                chunk("cloud", "same", "c", -1)), 100);
        assertThat(merged).hasSize(3);
    }

    @Test
    void missingDocumentIdentityNeverMerges() {
        assertThat(AdjacentMerger.apply(List.of(chunk("cloud", null, "same", 0),
                chunk("cloud", null, "same", 1)), 100)).hasSize(2);
    }

    @Test
    void mergedLengthLimitIncludesTheInsertedNewline() {
        var chunks = List.of(chunk("cloud", "same", "a", 0), chunk("cloud", "same", "b", 1));
        assertThat(AdjacentMerger.apply(chunks, 2)).hasSize(2);
        assertThat(AdjacentMerger.apply(chunks, 3)).singleElement()
                .extracting(RetrievedChunk::text).isEqualTo("a\nb");
    }

    private RetrievedChunk chunk(String source, String doc, String text, int index) {
        return new RetrievedChunk(source, doc, doc, text, index, null, null, null, text, text, 1, 1, null);
    }
}
