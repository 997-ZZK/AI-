package com.aiflow.engine.eval;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only, versioned fixtures with a fail-closed gate and reports that never contain payloads. */
final class OfflineGoldenSet {
    private static final int MAX_FIXTURE_BYTES = 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "suiteId", "goldenVersion", "cases");
    private static final Set<String> CASE_FIELDS = Set.of("id", "category", "scenario", "input", "expected");

    private final String resource;
    private final String suiteId;
    private final String goldenVersion;
    private final String fixtureSha256;
    private final List<Case> cases;
    private final Map<String, Case> byId;
    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();

    private OfflineGoldenSet(String resource, String suiteId, String goldenVersion, byte[] bytes, List<Case> cases) {
        this.resource = resource;
        this.suiteId = suiteId;
        this.goldenVersion = goldenVersion;
        this.fixtureSha256 = sha256(bytes);
        this.cases = List.copyOf(cases);
        this.byId = new LinkedHashMap<>();
        cases.forEach(testCase -> byId.put(testCase.id(), testCase));
    }

    static OfflineGoldenSet load(String resource, String suiteId, int minimumCases, Set<String> requiredCategories) {
        try (InputStream input = OfflineGoldenSet.class.getResourceAsStream(resource)) {
            if (input == null) throw invalid();
            return parse(resource, input.readNBytes(MAX_FIXTURE_BYTES + 1), suiteId, minimumCases, requiredCategories);
        } catch (IOException ex) {
            // JSON exceptions can contain fixture text; do not chain or print them.
            throw invalid();
        }
    }

    static OfflineGoldenSet parse(String resource, byte[] bytes, String suiteId, int minimumCases,
                                  Set<String> requiredCategories) {
        if (resource == null || !resource.matches("/eval/[a-z0-9-]+\\.json") || !identifier(suiteId)
                || bytes == null || bytes.length > MAX_FIXTURE_BYTES || minimumCases < 1
                || requiredCategories == null || requiredCategories.isEmpty()
                || requiredCategories.stream().anyMatch(category -> !identifier(category))) {
            throw invalid();
        }
        try {
            JsonNode root = MAPPER.readTree(bytes);
            requireFields(root, ROOT_FIELDS);
            if (requireInt(root, "schemaVersion") != 1 || !suiteId.equals(requireText(root, "suiteId"))) {
                throw invalid();
            }
            String version = requireText(root, "goldenVersion");
            if (!version.matches("[A-Za-z0-9._-]{1,64}")) throw invalid();
            JsonNode entries = root.get("cases");
            if (!entries.isArray() || entries.size() < minimumCases || entries.size() > 1000) throw invalid();
            Set<String> ids = new HashSet<>();
            Set<String> categories = new HashSet<>();
            List<Case> loaded = new ArrayList<>();
            for (JsonNode entry : entries) {
                requireFields(entry, CASE_FIELDS);
                String id = requireText(entry, "id");
                String category = requireText(entry, "category");
                String scenario = requireText(entry, "scenario");
                if (!identifier(id) || !identifier(scenario) || !ids.add(id)
                        || !requiredCategories.contains(category) || !entry.get("input").isObject()
                        || !entry.get("expected").isObject() || entry.get("expected").isEmpty()) {
                    throw invalid();
                }
                categories.add(category);
                loaded.add(new Case(id, category, scenario, entry.get("input"), entry.get("expected")));
            }
            if (!categories.equals(requiredCategories)) throw invalid();
            return new OfflineGoldenSet(resource, suiteId, version, bytes, loaded);
        } catch (IOException ex) {
            throw invalid();
        }
    }

    List<Case> cases() {
        return cases;
    }

    void verify(Case testCase, Executable assertions) throws Throwable {
        if (testCase == null || byId.get(testCase.id()) != testCase || assertions == null) throw invalid();
        if (outcomes.putIfAbsent(testCase.id(), new Outcome("FAIL", "NotCompleted")) != null) {
            outcomes.put(testCase.id(), new Outcome("FAIL", "DuplicateExecution"));
            throw new AssertionError("Golden case executed more than once");
        }
        try {
            assertions.execute();
            complete(testCase.id(), new Outcome("PASS", null));
        } catch (Throwable failure) {
            String type = failure.getClass().getSimpleName();
            complete(testCase.id(), new Outcome("FAIL",
                    type.matches("[A-Za-z0-9_$]{1,96}") ? type : "AssertionFailure"));
            throw failure;
        }
    }

    private void complete(String id, Outcome result) {
        // A concurrent duplicate is a permanent violation, even if the original assertion later succeeds.
        outcomes.compute(id, (ignored, previous) -> previous != null
                && "DuplicateExecution".equals(previous.failureType()) ? previous : result);
    }

    void writeReport() throws IOException {
        writeReport(Path.of("target", "m4-3d", "layer2-20261014-" + suiteId + "-report.json"));
    }

    void writeReport(Path path) throws IOException {
        int passed = 0;
        int failed = 0;
        int notRun = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Case testCase : cases) {
            Outcome result = outcomes.getOrDefault(testCase.id(), new Outcome("NOT_RUN", null));
            switch (result.status()) {
                case "PASS" -> passed++;
                case "FAIL" -> failed++;
                default -> notRun++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", testCase.id());
            row.put("category", testCase.category());
            row.put("status", result.status());
            if (result.failureType() != null) row.put("failureType", result.failureType());
            results.add(row);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("suiteId", suiteId);
        report.put("goldenVersion", goldenVersion);
        report.put("resource", resource);
        report.put("fixtureSha256", fixtureSha256);
        report.put("total", cases.size());
        report.put("passed", passed);
        report.put("failed", failed);
        report.put("notRun", notRun);
        report.put("passRate", (double) passed / cases.size());
        report.put("gate", passed == cases.size() ? "PASS" : "FAIL");
        report.put("cases", results);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        if (passed != cases.size()) throw new AssertionError("Offline golden gate failed; inspect case ids in report");
    }

    static void requireFields(JsonNode object, Set<String> fields) {
        if (object == null || !object.isObject()) throw invalid();
        Set<String> present = new HashSet<>();
        object.fieldNames().forEachRemaining(present::add);
        if (!present.equals(fields)) throw invalid();
    }

    static String requireText(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    static int requireInt(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }

    static boolean requireBoolean(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[a-z][a-z0-9-]{0,63}");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid offline golden fixture or assertion contract");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    record Case(String id, String category, String scenario, JsonNode input, JsonNode expected) {
        Case {
            input = input.deepCopy();
            expected = expected.deepCopy();
        }

        @Override public JsonNode input() { return input.deepCopy(); }
        @Override public JsonNode expected() { return expected.deepCopy(); }
    }

    private record Outcome(String status, String failureType) { }
}
