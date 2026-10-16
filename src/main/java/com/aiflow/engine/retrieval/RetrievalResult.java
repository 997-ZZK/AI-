package com.aiflow.engine.retrieval;

import com.aiflow.engine.retrieval.post.RetrievalStats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次检索的完整结果：编号切片 + 组装好的提示词块 + 各阶段统计。
 * chunkViews() 是给 API / node_runs / SSE 用的可溯源视图。
 */
public record RetrievalResult(
        String query,
        String kbId,
        List<String> sources,
        List<ContextChunk> chunks,
        String contextBlock,
        RetrievalStats stats
) {

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public List<String> citationLabels() {
        return chunks.stream().map(contextChunk -> contextChunk.chunk().citationLabel()).toList();
    }

    public List<Map<String, Object>> chunkViews() {
        List<Map<String, Object>> views = new ArrayList<>(chunks.size());
        for (ContextChunk contextChunk : chunks) {
            RetrievedChunk chunk = contextChunk.chunk();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("index", contextChunk.index());
            view.put("source", chunk.source());
            view.put("docId", chunk.docId());
            view.put("docTitle", chunk.docTitle());
            view.put("chunkId", chunk.chunkId());
            view.put("chunkIndex", chunk.chunkIndex());
            view.put("sectionPath", chunk.sectionPath());
            view.put("pageFrom", chunk.pageFrom());
            view.put("pageTo", chunk.pageTo());
            view.put("score", round(chunk.score()));
            view.put("rawScore", round(chunk.rawScore()));
            view.put("citation", chunk.citationLabel());
            views.add(view);
        }
        return views;
    }

    public static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
