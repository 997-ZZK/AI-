package com.aiflow.engine.retrieval;

/** 进入提示词的切片：index 就是资料编号 [n]，用于引用与溯源。 */
public record ContextChunk(int index, RetrievedChunk chunk) {
}
