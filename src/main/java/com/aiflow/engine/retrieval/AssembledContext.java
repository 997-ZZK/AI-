package com.aiflow.engine.retrieval;

import java.util.List;

public record AssembledContext(List<ContextChunk> chunks, String contextBlock) {

    public boolean isEmpty() {
        return chunks.isEmpty();
    }
}
