package com.aiflow.engine.retrieval;

/**
 * 分数归一化：把各来源量纲不同的 score 映射到 [0,1]，否则「低相似度丢弃 / 动态 top-k」跨来源不可比。
 */
public interface ScoreNormalizer {

    String source();

    double normalize(double rawScore);
}
