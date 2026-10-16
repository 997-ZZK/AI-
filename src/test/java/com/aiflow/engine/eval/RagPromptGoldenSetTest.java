package com.aiflow.engine.eval;

import com.aiflow.engine.retrieval.AssembledContext;
import com.aiflow.engine.retrieval.ContextChunk;
import com.aiflow.engine.retrieval.ContextPromptAssembler;
import com.aiflow.engine.retrieval.RetrievalResult;
import com.aiflow.engine.retrieval.RetrievedChunk;
import com.aiflow.engine.retrieval.post.ProcessedRetrieval;
import com.aiflow.engine.retrieval.post.RetrievalParams;
import com.aiflow.engine.retrieval.post.RetrievalPipeline;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.aiflow.engine.eval.OfflineGoldenSet.requireBoolean;
import static com.aiflow.engine.eval.OfflineGoldenSet.requireFields;
import static com.aiflow.engine.eval.OfflineGoldenSet.requireInt;
import static com.aiflow.engine.eval.OfflineGoldenSet.requireText;
import static org.assertj.core.api.Assertions.assertThat;

/** Real retrieval post-processing and prompt contracts; no model answer is graded here. */
class RagPromptGoldenSetTest {

    private static final Set<String> CATEGORIES = Set.of("refusal", "citations", "source-order", "prompt-boundary");
    private static final OfflineGoldenSet GOLDEN = OfflineGoldenSet.load("/eval/layer2-rag-golden.json",
            "rag", 10, CATEGORIES);
    private static final Set<String> CHUNK_FIELDS = Set.of("source", "docId", "docTitle", "chunkId", "chunkIndex",
            "sectionPath", "pageFrom", "pageTo", "text", "score");
    private static final Set<String> EXPECTED_FIELDS = Set.of("count", "empty", "sources", "docIds", "chunkIds",
            "chunkIndexes", "labels", "displayLabels", "displayBodies", "mustMention", "stats");
    private static final Pattern BOUNDARIES = Pattern.compile("<<<资料[0-9]+(?:结束)?>>>");

    @TestFactory
    Stream<DynamicTest> ragPromptContracts() {
        return GOLDEN.cases().stream().map(testCase -> DynamicTest.dynamicTest(testCase.id(),
                () -> GOLDEN.verify(testCase, () -> verifyCase(testCase))));
    }

    @AfterAll
    static void report() throws IOException {
        GOLDEN.writeReport();
    }

    private static void verifyCase(OfflineGoldenSet.Case testCase) {
        JsonNode input = testCase.input();
        JsonNode expected = testCase.expected();
        requireFields(input, Set.of("chunks", "minScore", "mergeAdjacent"));
        requireFields(expected, EXPECTED_FIELDS);
        List<RetrievedChunk> candidates = chunks(array(input, "chunks"));
        RetrievalParams params = new RetrievalParams(number(input, "minScore"), 8, 1, 8, 0.35, 0.7,
                requireBoolean(input, "mergeAdjacent"));
        ProcessedRetrieval processed = switch (testCase.scenario()) {
            case "pipeline" -> RetrievalPipeline.process(candidates, params);
            default -> throw new IllegalArgumentException("Unknown RAG golden scenario");
        };
        AssembledContext assembled = new ContextPromptAssembler().assemble(processed.chunks());
        RetrievalResult result = new RetrievalResult("synthetic-query", "synthetic-kb", List.of(), assembled.chunks(),
                assembled.contextBlock(), processed.stats());

        assertThat(result.chunks()).hasSize(requireInt(expected, "count"));
        assertThat(result.isEmpty()).isEqualTo(requireBoolean(expected, "empty"));
        assertThat(result.chunks().stream().map(item -> item.chunk().source()).toList())
                .isEqualTo(strings(array(expected, "sources")));
        assertThat(result.chunks().stream().map(item -> item.chunk().docId()).toList())
                .isEqualTo(strings(array(expected, "docIds")));
        assertThat(result.chunks().stream().map(item -> item.chunk().chunkId()).toList())
                .isEqualTo(strings(array(expected, "chunkIds")));
        assertThat(result.chunks().stream().map(item -> item.chunk().chunkIndex()).toList())
                .isEqualTo(integers(array(expected, "chunkIndexes")));
        // Compare payloads as booleans so a failed assertion cannot print prompt or metadata bodies into logs.
        assertThat(result.citationLabels().equals(strings(array(expected, "labels"))))
                .as("Original citation metadata must match its independent golden values").isTrue();
        verifyPrompt(assembled, expected);
        verifyTraceability(result, processed.chunks());
        verifyOriginals(candidates, array(input, "chunks"));

        JsonNode stats = expected.get("stats");
        requireFields(stats, Set.of("raw", "afterThreshold", "afterMerge"));
        assertThat(processed.stats().raw()).isEqualTo(requireInt(stats, "raw"));
        assertThat(processed.stats().afterThreshold()).isEqualTo(requireInt(stats, "afterThreshold"));
        assertThat(processed.stats().afterMerge()).isEqualTo(requireInt(stats, "afterMerge"));
    }

    private static void verifyPrompt(AssembledContext assembled, JsonNode expected) {
        String block = assembled.contextBlock();
        List<String> labels = strings(array(expected, "displayLabels"));
        List<String> bodies = strings(array(expected, "displayBodies"));
        assertThat(labels).hasSize(assembled.chunks().size());
        assertThat(bodies).hasSize(assembled.chunks().size());
        for (String fragment : strings(array(expected, "mustMention"))) {
            assertThat(block.contains(fragment)).as("Prompt must retain its declared usage rules").isTrue();
        }
        assertThat(BOUNDARIES.matcher(block).results().count()).isEqualTo(assembled.chunks().size() * 2L);
        int previousEnd = -1;
        for (int offset = 0; offset < assembled.chunks().size(); offset++) {
            int index = offset + 1;
            assertThat(assembled.chunks().get(offset).index()).isEqualTo(index);
            String start = "<<<资料" + index + ">>>";
            String end = "<<<资料" + index + "结束>>>";
            assertThat(occurrences(block, start)).isEqualTo(1);
            assertThat(occurrences(block, end)).isEqualTo(1);
            int startAt = block.indexOf(start);
            int endAt = block.indexOf(end);
            assertThat(startAt).isGreaterThan(previousEnd);
            assertThat(endAt).isGreaterThan(startAt);
            previousEnd = endAt + end.length() - 1;
            String section = "[" + index + "] " + labels.get(offset) + "\n" + start + "\n"
                    + bodies.get(offset) + "\n" + end;
            assertThat(block.contains(section)).as("Expected citation label and body must stay in their own block")
                    .isTrue();
            assertThat(labels.get(offset).lines().count()).isEqualTo(1L);
        }
        if (assembled.isEmpty()) {
            assertThat(block.contains("本次没有检索到可用资料")).as("Empty retrieval must request refusal").isTrue();
            assertThat(block.contains("[1]")).as("Empty retrieval must not fabricate citations").isFalse();
        }
    }

    private static void verifyTraceability(RetrievalResult result, List<RetrievedChunk> processed) {
        List<Map<String, Object>> views = result.chunkViews();
        assertThat(views).hasSize(processed.size());
        for (int index = 0; index < processed.size(); index++) {
            RetrievedChunk original = processed.get(index);
            ContextChunk numbered = result.chunks().get(index);
            assertThat(numbered.chunk()).isSameAs(original);
            Map<String, Object> view = views.get(index);
            assertThat(view.get("index")).isEqualTo(index + 1);
            assertThat(view.get("source")).isEqualTo(original.source());
            assertThat(view.get("docId")).isEqualTo(original.docId());
            assertThat(view.get("chunkId")).isEqualTo(original.chunkId());
            assertThat(view.get("chunkIndex")).isEqualTo(original.chunkIndex());
            assertThat(view.get("pageFrom")).isEqualTo(original.pageFrom());
            assertThat(view.get("pageTo")).isEqualTo(original.pageTo());
            assertThat(java.util.Objects.equals(view.get("docTitle"), original.docTitle())).isTrue();
            assertThat(java.util.Objects.equals(view.get("sectionPath"), original.sectionPath())).isTrue();
            assertThat(java.util.Objects.equals(view.get("citation"), original.citationLabel())).isTrue();
        }
    }

    private static void verifyOriginals(List<RetrievedChunk> candidates, JsonNode input) {
        assertThat(candidates).hasSize(input.size());
        for (int index = 0; index < candidates.size(); index++) {
            RetrievedChunk original = candidates.get(index);
            JsonNode fixture = input.get(index);
            assertThat(original.text().equals(requireText(fixture, "text"))).as("Raw source body is unchanged").isTrue();
            assertThat(java.util.Objects.equals(original.docTitle(), nullableText(fixture, "docTitle"))).isTrue();
            assertThat(java.util.Objects.equals(original.sectionPath(), nullableText(fixture, "sectionPath"))).isTrue();
        }
    }

    private static List<RetrievedChunk> chunks(JsonNode input) {
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (JsonNode chunk : input) {
            requireFields(chunk, CHUNK_FIELDS);
            double score = number(chunk, "score");
            chunks.add(new RetrievedChunk(requireText(chunk, "source"), requireText(chunk, "docId"),
                    nullableText(chunk, "docTitle"), requireText(chunk, "chunkId"), requireInt(chunk, "chunkIndex"),
                    nullableText(chunk, "sectionPath"), nullableInteger(chunk, "pageFrom"), nullableInteger(chunk, "pageTo"),
                    requireText(chunk, "text"), null, score, score, null));
        }
        return List.copyOf(chunks);
    }

    private static JsonNode array(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isArray()) throw new IllegalArgumentException("Expected RAG fixture array");
        return value;
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw new IllegalArgumentException("Expected RAG fixture text");
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static List<Integer> integers(JsonNode values) {
        List<Integer> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw new IllegalArgumentException("Expected RAG fixture integer");
            }
            result.add(value.intValue());
        }
        return List.copyOf(result);
    }

    private static String nullableText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isNull() ? null : requireText(object, field);
    }

    private static Integer nullableInteger(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isNull() ? null : requireInt(object, field);
    }

    private static double number(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())
                || value.doubleValue() < 0 || value.doubleValue() > 1) {
            throw new IllegalArgumentException("Expected normalized RAG fixture score");
        }
        return value.doubleValue();
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int index = text.indexOf(token); index >= 0; index = text.indexOf(token, index + token.length())) {
            count++;
        }
        return count;
    }
}
