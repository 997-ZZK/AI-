package com.aiflow.engine.retrieval.post;

import com.aiflow.engine.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 相邻切片合并：同一文档里 chunkIndex 连续且合并后不超长的切片拼成一段，
 * 避免「一句话被切成两半、引用只能引半句」。合并结果按分数降序输出。
 */
public final class AdjacentMerger {

    public static final int DEFAULT_MAX_MERGED_CHARS = 1200;

    private AdjacentMerger() {
    }

    public static List<RetrievedChunk> apply(List<RetrievedChunk> chunks, int maxMergedChars) {
        Map<DocumentKey, List<RetrievedChunk>> byDocument = new LinkedHashMap<>();
        List<RetrievedChunk> merged = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk.chunkIndex() < 0 || chunk.docId() == null) {
                merged.add(chunk);
                continue;
            }
            DocumentKey key = new DocumentKey(chunk.source(), chunk.docId());
            byDocument.computeIfAbsent(key, ignored -> new ArrayList<>()).add(chunk);
        }
        for (List<RetrievedChunk> group : byDocument.values()) {
            group.sort(Comparator.comparingInt(RetrievedChunk::chunkIndex));
            RetrievedChunk current = null;
            StringBuilder text = null;
            int lastIndex = Integer.MIN_VALUE;
            for (RetrievedChunk chunk : group) {
                if (current == null) {
                    current = chunk;
                    text = new StringBuilder(chunk.text() == null ? "" : chunk.text());
                    lastIndex = chunk.chunkIndex();
                    continue;
                }
                boolean adjacent = (long) chunk.chunkIndex() == (long) lastIndex + 1;
                boolean fits = (long) text.length() + 1 + (chunk.text() == null ? 0 : chunk.text().length()) <= maxMergedChars;
                if (adjacent && fits) {
                    text.append('\n').append(chunk.text() == null ? "" : chunk.text());
                    current = current.mergedWith(chunk, text.toString());
                    lastIndex = chunk.chunkIndex();
                } else {
                    merged.add(current);
                    current = chunk;
                    text = new StringBuilder(chunk.text() == null ? "" : chunk.text());
                    lastIndex = chunk.chunkIndex();
                }
            }
            if (current != null) {
                merged.add(current);
            }
        }
        merged.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                .thenComparing(chunk -> chunk.docId() == null ? "" : chunk.docId())
                .thenComparingInt(RetrievedChunk::chunkIndex));
        return List.copyOf(merged);
    }

    private record DocumentKey(String source, String docId) { }
}
