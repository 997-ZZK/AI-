package com.aiflow.engine.retrieval.post;

import com.aiflow.engine.retrieval.RetrievedChunk;

import java.util.List;

public record ProcessedRetrieval(List<RetrievedChunk> chunks, RetrievalStats stats) {
}
