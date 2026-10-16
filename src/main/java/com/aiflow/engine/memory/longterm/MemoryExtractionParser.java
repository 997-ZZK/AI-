package com.aiflow.engine.memory.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 抽取结果的解析与清洗（**纯函数**，不碰库、不碰网络）。
 *
 * <p>口径：解析失败**整批丢弃**并记账，不做「尽力猜」。理由与内容安全同一取舍 ——
 * 猜错的代价是错记一条会被后续每次对话重复复述的「事实」，而丢一批的代价只是这轮没记住。
 *
 * <p>多层清洗，每层各自记账（{@link Rejected#reason()}）：
 * <ol>
 *   <li>{@code invalid-json} / {@code not-array}：模型没按格式回；</li>
 *   <li>{@code bad-kind} / {@code bad-key} / {@code empty-value}：结构不对；</li>
 *   <li>{@code too-long}：单条正文超上限（**丢弃不截断** —— 被切断的事实比没有事实更容易误导）；</li>
 *   <li>{@code low-confidence}：低于阈值；</li>
 *   <li>{@code sensitive:xxx}：命中禁写清单；{@code safety}：命中内容安全规则；</li>
 *   <li>{@code duplicate}：同一批里 key 撞车（留置信度高的那条）；{@code over-limit}：超出单次上限。</li>
 * </ol>
 */
public final class MemoryExtractionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int EVIDENCE_MAX_CHARS = 500;
    public static final int DISPLAY_KEY_MAX_CHARS = 200;

    private MemoryExtractionParser() {
    }

    public record Rejected(String key, String reason) {
    }

    public record Outcome(List<MemoryCandidate> candidates, List<Rejected> rejected) {
    }

    /**
     * @param blockedBySafety 内容安全二次过滤（生产传 {@code text -> !contentSafety.scanRules(text).allowed()}）
     */
    public static Outcome parse(String raw, LongMemoryProperties properties, Predicate<String> blockedBySafety) {
        List<Rejected> rejected = new ArrayList<>();
        String json = extractArray(raw);
        if (json == null) {
            rejected.add(new Rejected("", "invalid-json"));
            return new Outcome(List.of(), List.copyOf(rejected));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            rejected.add(new Rejected("", "invalid-json"));
            return new Outcome(List.of(), List.copyOf(rejected));
        }
        if (root == null || !root.isArray()) {
            rejected.add(new Rejected("", "not-array"));
            return new Outcome(List.of(), List.copyOf(rejected));
        }
        Map<String, MemoryCandidate> unique = new LinkedHashMap<>();
        for (JsonNode node : root) {
            MemoryCandidate candidate = clean(node, properties, blockedBySafety, rejected);
            if (candidate == null) {
                continue;
            }
            String slot = candidate.kind().name() + "|" + candidate.memKey();
            MemoryCandidate previous = unique.get(slot);
            if (previous == null) {
                unique.put(slot, candidate);
                continue;
            }
            // 同一批里两条同 key：留置信度高的（模型自己给了强弱），另一条留痕
            boolean keepNew = candidate.confidence() > previous.confidence();
            unique.put(slot, keepNew ? candidate : previous);
            rejected.add(new Rejected(candidate.displayKey(), "duplicate"));
        }
        List<MemoryCandidate> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparingDouble(MemoryCandidate::confidence).reversed());
        int limit = properties.extractMaxCandidates();
        if (ordered.size() > limit) {
            for (MemoryCandidate extra : ordered.subList(limit, ordered.size())) {
                rejected.add(new Rejected(extra.displayKey(), "over-limit"));
            }
            ordered = new ArrayList<>(ordered.subList(0, limit));
        }
        return new Outcome(List.copyOf(ordered), List.copyOf(rejected));
    }

    private static MemoryCandidate clean(JsonNode node, LongMemoryProperties properties,
                                         Predicate<String> blockedBySafety, List<Rejected> rejected) {
        if (node == null || !node.isObject()) {
            rejected.add(new Rejected("", "not-object"));
            return null;
        }
        String rawKey = node.path("key").asText("");
        MemoryKind kind = MemoryKind.parse(node.path("kind").asText(""));
        if (kind == null) {
            rejected.add(new Rejected(rawKey, "bad-kind"));
            return null;
        }
        String displayKey = truncate(rawKey.strip(), DISPLAY_KEY_MAX_CHARS);
        String memKey = MemoryKeys.normalize(displayKey);
        if (memKey.isEmpty()) {
            rejected.add(new Rejected(displayKey, "bad-key"));
            return null;
        }
        String value = node.path("value").asText("").strip();
        if (value.isEmpty()) {
            rejected.add(new Rejected(displayKey, "empty-value"));
            return null;
        }
        if (value.length() > properties.extractMaxChars()) {
            rejected.add(new Rejected(displayKey, "too-long"));
            return null;
        }
        String guard = MemoryWriteGuard.check(displayKey + " " + value);
        if (guard != null) {
            rejected.add(new Rejected(displayKey, "sensitive:" + guard));
            return null;
        }
        if (blockedBySafety != null && blockedBySafety.test(value)) {
            rejected.add(new Rejected(displayKey, "safety"));
            return null;
        }
        double confidence = node.path("confidence").asDouble(0.5);
        if (confidence < properties.extractMinConfidence()) {
            rejected.add(new Rejected(displayKey, "low-confidence"));
            return null;
        }
        confidence = Math.min(1.0, Math.max(0.0, confidence));
        String evidence = truncate(node.path("evidence").asText("").strip(), EVIDENCE_MAX_CHARS);
        boolean supersedes = node.path("supersedes").asBoolean(false);
        return new MemoryCandidate(kind, displayKey, memKey, value, confidence, evidence, supersedes);
    }

    /**
     * 从模型回复里取出 JSON 数组这一段：模型常带上解释或代码块围栏，剥掉它们不算「尽力猜」——
     * 剥掉之后仍然必须是一个合法 JSON 数组，否则整批丢弃。
     */
    static String extractArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        int start = text.indexOf((char) 91);
        int end = text.lastIndexOf((char) 93);
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
