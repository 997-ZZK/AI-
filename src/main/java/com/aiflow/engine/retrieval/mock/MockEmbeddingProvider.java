package com.aiflow.engine.retrieval.mock;

import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.Vectors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 确定性 mock embedding：signed hash kernel，把文本映射成固定维度稀疏向量。
 *
 * 用途：离线跑黄金集回归、单测、压测（不烧钱、不依赖网络、结果可复现）。
 * 局限：只对「词面重叠」敏感，同义改写召回弱 —— 生产必须换真实 embedding provider（见 HttpEmbeddingProvider）。
 */
public class MockEmbeddingProvider implements EmbeddingProvider {

    private final String id;
    private final int dimensions;

    public MockEmbeddingProvider(String id, int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions 必须为正整数: " + dimensions);
        }
        this.id = id;
        this.dimensions = dimensions;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embedOne(text));
        }
        return vectors;
    }

    float[] embedOne(String text) {
        float[] vector = new float[dimensions];
        Map<String, Integer> counts = tokenize(text);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            long hash = fnv1a(entry.getKey());
            int index = (int) Math.floorMod(hash, dimensions);
            float sign = ((hash >>> 33) & 1L) == 0L ? 1f : -1f;
            float weight = (float) (1.0 + Math.log(entry.getValue()));
            vector[index] += sign * weight;
        }
        return Vectors.normalize(vector);
    }

    /** 中文取单字 + 相邻双字，英文/数字取整词，兼顾中文分词缺失与英文词形。 */
    static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> counts = new HashMap<>();
        if (text == null || text.isBlank()) {
            return counts;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        char previousCjk = 0;
        for (int index = 0; index < lowered.length(); index++) {
            char current = lowered.charAt(index);
            if (isCjk(current)) {
                flushWord(counts, word);
                add(counts, String.valueOf(current));
                if (previousCjk != 0) {
                    add(counts, previousCjk + String.valueOf(current));
                }
                previousCjk = current;
            } else if (Character.isLetterOrDigit(current)) {
                previousCjk = 0;
                word.append(current);
            } else {
                previousCjk = 0;
                flushWord(counts, word);
            }
        }
        flushWord(counts, word);
        return counts;
    }

    private static void flushWord(Map<String, Integer> counts, StringBuilder word) {
        if (word.length() > 0) {
            add(counts, word.toString());
            word.setLength(0);
        }
    }

    private static void add(Map<String, Integer> counts, String token) {
        counts.merge(token, 1, Integer::sum);
    }

    private static boolean isCjk(char value) {
        return value >= '\u4e00' && value <= '\u9fff';
    }

    private static long fnv1a(String token) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : token.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (value & 0xffL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
