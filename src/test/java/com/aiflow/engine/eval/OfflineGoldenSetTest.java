package com.aiflow.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfflineGoldenSetTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOURCE = "/eval/layer2-harness-golden.json";
    @TempDir Path temporary;

    @Test
    void rejectsEmptyDuplicateMissingCategoryAndInsufficientFixtures() throws Exception {
        ObjectNode empty = fixture();
        empty.putArray("cases");
        assertInvalid(empty);
        ObjectNode duplicate = fixture();
        duplicate.withArray("cases").add(duplicate.withArray("cases").get(0).deepCopy());
        assertInvalid(duplicate);
        assertThatThrownBy(() -> OfflineGoldenSet.parse(RESOURCE, bytes(fixture()), "harness", 2, Set.of("boundary")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfflineGoldenSet.parse(RESOURCE, bytes(fixture()), "harness", 1,
                Set.of("boundary", "missing"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongVersionsUnknownFieldsUnknownCategoriesAndMissingExpected() throws Exception {
        ObjectNode version = fixture();
        version.put("schemaVersion", 2);
        assertInvalid(version);
        ObjectNode unknown = fixture();
        unknown.put("ignoredGate", true);
        assertInvalid(unknown);
        ObjectNode category = fixture();
        ((ObjectNode) category.withArray("cases").get(0)).put("category", "unknown");
        assertInvalid(category);
        ObjectNode missingExpected = fixture();
        ((ObjectNode) missingExpected.withArray("cases").get(0)).remove("expected");
        assertInvalid(missingExpected);
    }

    @Test
    void duplicateJsonFieldsAndMalformedPayloadsFailWithoutEchoingThePayload() {
        for (String content : new String[]{"{\"synthetic-private-marker\":", "{\"schemaVersion\":1,\"schemaVersion\":2}"}) {
            assertThatThrownBy(() -> OfflineGoldenSet.parse(RESOURCE, content.getBytes(StandardCharsets.UTF_8),
                    "harness", 1, Set.of("boundary")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid offline golden fixture or assertion contract")
                    .hasNoCause();
        }
    }

    @Test
    void trailingJsonCannotHideAdditionalFixtureContent() throws Exception {
        byte[] trailing = (MAPPER.writeValueAsString(fixture()) + " {\"ignored\":true}")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> OfflineGoldenSet.parse(RESOURCE, trailing, "harness", 1, Set.of("boundary")))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test
    void notRunCaseProducesFailedReportInsteadOfAnEmptyGreenGate() throws Exception {
        OfflineGoldenSet set = loaded();
        Path report = temporary.resolve("not-run.json");
        assertThatThrownBy(() -> set.writeReport(report)).isInstanceOf(AssertionError.class);
        JsonNode result = MAPPER.readTree(report.toFile());
        assertThat(result.path("gate").asText()).isEqualTo("FAIL");
        assertThat(result.path("notRun").asInt()).isEqualTo(1);
        assertThat(result.path("passRate").asDouble()).isZero();
    }

    @Test
    void failedCaseReportContainsOnlySafeMetadata() throws Exception {
        OfflineGoldenSet set = loaded();
        assertThatThrownBy(() -> set.verify(set.cases().getFirst(), () -> {
            throw new AssertionError("synthetic-private-failure-message");
        })).isInstanceOf(AssertionError.class);
        Path report = temporary.resolve("failure.json");
        assertThatThrownBy(() -> set.writeReport(report)).isInstanceOf(AssertionError.class);
        String serialized = Files.readString(report);
        assertThat(serialized).doesNotContain("synthetic-private-input", "synthetic-private-expected",
                "synthetic-private-failure-message", "scenario", "input", "expected");
        JsonNode result = MAPPER.readTree(serialized);
        assertThat(result.path("failed").asInt()).isEqualTo(1);
        assertThat(result.path("cases").get(0).path("failureType").asText()).isEqualTo("AssertionError");
    }

    @Test
    void successfulReportPinsFixtureHashAndDetectsRepeatedExecution() throws Throwable {
        OfflineGoldenSet set = loaded();
        set.verify(set.cases().getFirst(), () -> { });
        Path report = temporary.resolve("passed.json");
        set.writeReport(report);
        JsonNode result = MAPPER.readTree(report.toFile());
        assertThat(result.path("gate").asText()).isEqualTo("PASS");
        assertThat(result.path("passRate").asDouble()).isEqualTo(1);
        assertThat(result.path("fixtureSha256").asText()).matches("[a-f0-9]{64}");
        assertThatThrownBy(() -> set.verify(set.cases().getFirst(), () -> { })).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> set.writeReport(report)).isInstanceOf(AssertionError.class);
        assertThat(MAPPER.readTree(report.toFile()).path("gate").asText()).isEqualTo("FAIL");
    }

    @Test
    void concurrentDuplicateCannotBeOverwrittenByAnEarlierSuccess() throws Exception {
        OfflineGoldenSet set = loaded();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var original = executor.submit(() -> {
                    try {
                        set.verify(set.cases().getFirst(), () -> {
                            entered.countDown();
                            assertThat(finish.await(2, TimeUnit.SECONDS)).isTrue();
                        });
                    } catch (Throwable failure) {
                        throw new AssertionError("Original fixture assertion failed", failure);
                    }
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> set.verify(set.cases().getFirst(), () -> { }))
                        .isInstanceOf(AssertionError.class);
                finish.countDown();
                original.get(2, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }
        Path report = temporary.resolve("concurrent-duplicate.json");
        assertThatThrownBy(() -> set.writeReport(report)).isInstanceOf(AssertionError.class);
        JsonNode result = MAPPER.readTree(report.toFile());
        assertThat(result.path("gate").asText()).isEqualTo("FAIL");
        assertThat(result.path("cases").get(0).path("failureType").asText()).isEqualTo("DuplicateExecution");
    }

    @Test
    void scenarioContractAccessorsRejectMissingOrWrongTypedExpectations() throws Exception {
        JsonNode expected = MAPPER.readTree("{\"flag\":true,\"count\":1,\"text\":\"value\"}");
        assertThatThrownBy(() -> OfflineGoldenSet.requireBoolean(expected, "missing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfflineGoldenSet.requireInt(expected, "flag"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfflineGoldenSet.requireText(expected, "count"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfflineGoldenSet.requireFields(expected, Set.of("flag", "count")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OfflineGoldenSet loaded() throws Exception {
        return OfflineGoldenSet.parse(RESOURCE, bytes(fixture()), "harness", 1, Set.of("boundary"));
    }

    private static void assertInvalid(JsonNode fixture) throws Exception {
        assertThatThrownBy(() -> OfflineGoldenSet.parse(RESOURCE, bytes(fixture), "harness", 1, Set.of("boundary")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(JsonNode fixture) throws Exception {
        return MAPPER.writeValueAsBytes(fixture);
    }

    private static ObjectNode fixture() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"schemaVersion":1,"suiteId":"harness","goldenVersion":"20261014-v1","cases":[
                  {"id":"fixture-01","category":"boundary","scenario":"fixed-contract",
                   "input":{"text":"synthetic-private-input"},
                   "expected":{"text":"synthetic-private-expected"}}
                ]}
                """);
    }
}
