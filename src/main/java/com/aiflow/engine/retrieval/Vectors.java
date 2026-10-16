package com.aiflow.engine.retrieval;

import java.util.Locale;

/**
 * 向量工具：归一化、余弦相似度、pgvector 文本字面量互转。
 * 纯函数，无 Spring/DB 依赖。
 */
public final class Vectors {

    private static final double EPSILON = 1e-9;

    private Vectors() {
    }

    public static float[] normalize(float[] vector) {
        double norm = norm(vector);
        if (norm < EPSILON) {
            return vector.clone();
        }
        float[] normalized = new float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            normalized[index] = (float) (vector[index] / norm);
        }
        return normalized;
    }

    public static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
        }
        double denominator = norm(left) * norm(right);
        if (denominator < EPSILON) {
            return 0;
        }
        double similarity = dot / denominator;
        return Math.max(-1.0, Math.min(1.0, similarity));
    }

    /** 解析 pgvector 文本字面量，例如 "[0.1,-0.2]"；空值或非法值返回 null。 */
    public static float[] parse(String literal) {
        if (literal == null) {
            return null;
        }
        String trimmed = literal.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || trimmed.charAt(trimmed.length() - 1) != ']') {
            return null;
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int index = 0; index < parts.length; index++) {
            try {
                vector[index] = Float.parseFloat(parts[index].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return vector;
    }

    /** 生成 pgvector 文本字面量；写库时配合 cast(? as vector) 使用。 */
    public static String toLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8 + 2);
        builder.append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(String.format(Locale.ROOT, "%.6f", vector[index]));
        }
        return builder.append(']').toString();
    }

    public static void requireDimensions(float[] vector, int expected, String source) {
        if (vector == null) {
            throw new IllegalArgumentException(source + " 返回空向量");
        }
        if (vector.length != expected) {
            throw new IllegalArgumentException(
                    source + " 向量维度不符：期望 " + expected + "，实际 " + vector.length);
        }
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }
}
