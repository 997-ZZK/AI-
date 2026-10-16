package com.aiflow.engine.api;

import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.persistence.entity.NodeRunEntity;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.persistence.repo.NodeRunRepository;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.run.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:run-replay;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "aiflow.approval.sweep-enabled=false",
        "aiflow.tools.audit.enabled=false",
        "aiflow.tools.audit.persist=false"
})
@AutoConfigureMockMvc
@Import(RunReplayIntegrationTest.SqlInspectionConfiguration.class)
@Transactional
class RunReplayIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-10-14T01:00:00Z");
    private static final Set<String> SUMMARY_FIELDS = Set.of("nodeRunId", "nodeId", "nodeType", "attempt",
            "status", "startedAt", "endedAt", "elapsedMs");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WorkflowRunRepository runs;
    @Autowired private NodeRunRepository nodes;
    @Autowired private EntityManager entityManager;
    private UUID runId;

    @BeforeEach
    void createRun() {
        runId = saveRun().getId();
    }

    @AfterEach
    void stopSqlInspection() {
        ReplaySqlInspector.finish();
    }

    @Test
    void listUsesBoundedSqlProjectionAndNeverLoadsSnapshotBodies() throws Exception {
        for (int i = 1; i <= 51; i++) {
            saveNode(runId, i, "node-" + i, 1, NodeRunStatus.SUCCEEDED, BASE.plusSeconds(i), BASE.plusSeconds(i + 1));
        }
        entityManager.flush();
        entityManager.clear();
        ReplaySqlInspector.begin();

        JsonNode data = list(0, 50);
        List<String> statements = ReplaySqlInspector.finish();
        assertThat(data.path("items")).hasSize(50);
        assertThat(data.path("page").asInt()).isZero();
        assertThat(data.path("size").asInt()).isEqualTo(50);
        assertThat(data.path("hasNext").asBoolean()).isTrue();
        assertThat(fieldNames(data.path("items").get(0))).isEqualTo(SUMMARY_FIELDS);
        assertThat(data.toString()).doesNotContain("synthetic-input", "synthetic-output", "synthetic-error");
        List<String> nodeQueries = statements.stream().filter(sql -> sql.contains(" from node_runs ")).toList();
        assertThat(nodeQueries).hasSize(1);
        String sql = nodeQueries.getFirst();
        assertThat(sql).doesNotContain(".input", ".output", ".error");
        assertThat(sql).contains("order by case", "fetch first");
        assertThat(sql).doesNotContain("count(");
        ReplaySqlInspector.begin();
        assertThat(list(1, 50).path("items")).hasSize(1);
        List<String> secondPageSql = ReplaySqlInspector.finish();
        assertThat(secondPageSql.stream().filter(query -> query.contains(" from node_runs ")))
                .singleElement().satisfies(query -> assertThat(query).contains("offset", "fetch first"));
    }

    @Test
    void pagesUseStableTimeThenIdOrderWithNullStartsLast() throws Exception {
        saveNode(runId, 3, "later-id", 1, NodeRunStatus.SUCCEEDED, BASE, BASE.plusSeconds(1));
        saveNode(runId, 1, "earlier-id", 1, NodeRunStatus.SUCCEEDED, BASE, BASE.plusSeconds(1));
        saveNode(runId, 2, "earlier-time", 1, NodeRunStatus.SUCCEEDED, BASE.minusSeconds(1), BASE);
        saveNode(runId, 5, "null-later-id", 1, NodeRunStatus.RUNNING, null, null);
        saveNode(runId, 4, "null-earlier-id", 1, NodeRunStatus.RUNNING, null, null);

        JsonNode first = list(0, 2);
        JsonNode middle = list(1, 2);
        JsonNode last = list(2, 2);
        assertThat(ids(first)).containsExactly(id(2).toString(), id(1).toString());
        assertThat(ids(middle)).containsExactly(id(3).toString(), id(4).toString());
        assertThat(ids(list(1, 2))).isEqualTo(ids(middle));
        assertThat(ids(last)).containsExactly(id(5).toString());
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(middle.path("hasNext").asBoolean()).isTrue();
        assertThat(last.path("hasNext").asBoolean()).isFalse();
        assertThat(list(3, 2).path("items")).isEmpty();
    }

    @Test
    void repeatedNodeAttemptsRemainSeparateWithTheirOwnStatuses() throws Exception {
        saveNode(runId, 1, "chat", 1, NodeRunStatus.FAILED, BASE, BASE.plusMillis(50));
        saveNode(runId, 2, "chat", 2, NodeRunStatus.SUCCEEDED, BASE.plusMillis(60), BASE.plusMillis(80));
        JsonNode items = list(0, 50).path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("attempt").asInt()).isEqualTo(1);
        assertThat(items.get(0).path("status").asText()).isEqualTo("FAILED");
        assertThat(items.get(1).path("attempt").asInt()).isEqualTo(2);
        assertThat(items.get(1).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(items.get(1).path("nodeId").asText()).isEqualTo(items.get(0).path("nodeId").asText());
        assertThat(items.get(0).path("elapsedMs").asLong()).isEqualTo(50);
        assertThat(items.get(1).path("elapsedMs").asLong()).isEqualTo(20);
    }

    @Test
    void defaultAndMaximumPageSizesAreAccepted() throws Exception {
        saveNode(runId, 1, "chat", 1, NodeRunStatus.SUCCEEDED, BASE, BASE.plusSeconds(1));
        mockMvc.perform(get("/api/v1/runs/{runId}/nodes", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        assertThat(list(0, 200).path("items")).hasSize(1);
    }

    @Test
    void emptyRunAndLegalOutOfRangePagesStayEmpty() throws Exception {
        JsonNode first = list(0, 50);
        assertThat(first.path("items")).isEmpty();
        assertThat(first.path("hasNext").asBoolean()).isFalse();
        assertThat(list(42, 50).path("items")).isEmpty();
        assertThat(list(Integer.MAX_VALUE, 1).path("items")).isEmpty();
    }

    @Test
    void listForMissingRunIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/runs/{runId}/nodes", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void detailReturnsOneFlatSnapshotAndAllSummaryFields() throws Exception {
        saveNode(runId, 1, "chat", 2, NodeRunStatus.FAILED, BASE, BASE.plusMillis(250));
        JsonNode data = detail(runId, id(1));
        assertThat(data.path("nodeRunId").asText()).isEqualTo(id(1).toString());
        assertThat(data.path("attempt").asInt()).isEqualTo(2);
        assertThat(data.path("elapsedMs").asLong()).isEqualTo(250);
        assertThat(data.path("input").path("text").asText()).isEqualTo("synthetic-input");
        assertThat(data.path("output").path("text").asText()).isEqualTo("synthetic-output");
        assertThat(data.path("error").asText()).isEqualTo("synthetic-error");
        var fields = new java.util.HashSet<>(SUMMARY_FIELDS);
        fields.addAll(Set.of("input", "output", "error"));
        assertThat(fieldNames(data)).isEqualTo(fields);
    }

    @Test
    void detailsForUnknownOrDifferentRunsShareNotFoundWithoutLeakingBodies() throws Exception {
        UUID otherRun = saveRun().getId();
        saveNode(otherRun, 1, "private-node", 1, NodeRunStatus.FAILED, BASE, BASE.plusSeconds(1));
        for (UUID requestedRun : List.of(runId, UUID.randomUUID())) {
            mockMvc.perform(get("/api/v1/runs/{runId}/nodes/{nodeRunId}", requestedRun, id(1)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400))
                    .andExpect(jsonPath("$.message").value(not(containsString("synthetic-"))))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        mockMvc.perform(get("/api/v1/runs/{runId}/nodes/{nodeRunId}", runId, UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
    }

    static Stream<Arguments> elapsedCases() {
        return Stream.of(
                Arguments.of(null, BASE, null),
                Arguments.of(BASE, null, null),
                Arguments.of(BASE, BASE.minusMillis(1), null),
                Arguments.of(BASE, BASE, 0L),
                Arguments.of(BASE, BASE.plusSeconds(3600), 3_600_000L));
    }

    @ParameterizedTest
    @MethodSource("elapsedCases")
    void durationIsNullUnlessFinishedAndIncludesApprovalWaits(Instant start, Instant end, Long expected) throws Exception {
        NodeRunEntity node = saveNode(runId, 1, "approval", 1, NodeRunStatus.SUCCEEDED, start, end);
        node.setNodeType("approval.request");
        nodes.saveAndFlush(node);
        JsonNode summary = list(0, 50).path("items").get(0);
        JsonNode snapshot = detail(runId, id(1));
        if (expected == null) {
            assertThat(summary.path("elapsedMs").isNull()).isTrue();
            assertThat(snapshot.path("elapsedMs").isNull()).isTrue();
        } else {
            assertThat(summary.path("elapsedMs").asLong()).isEqualTo(expected);
            assertThat(snapshot.path("elapsedMs").asLong()).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @CsvSource({"-1,50", "0,0", "0,-1", "0,201", "2147483647,200", "2147483648,1", "oops-replay,50", "0,oops-replay"})
    void invalidPageParametersAreBadRequests(String page, String size) throws Exception {
        mockMvc.perform(get("/api/v1/runs/{runId}/nodes", runId).param("page", page).param("size", size))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(not(containsString("oops-replay"))));
    }

    @Test
    void malformedRunAndNodeIdsAreBadRequests() throws Exception {
        for (String path : List.of("/api/v1/runs/not-a-uuid/nodes",
                "/api/v1/runs/" + runId + "/nodes/not-a-uuid")) {
            mockMvc.perform(get(path)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
    }

    @Test
    void snapshotGetsDoNotModifyRunOrAttemptAndOriginalRunResponseRemainsIntact() throws Exception {
        saveNode(runId, 1, "approval", 1, NodeRunStatus.WAITING, BASE, null);
        entityManager.flush();
        entityManager.clear();
        JsonNode runBefore = mapper.valueToTree(runs.findById(runId).orElseThrow());
        JsonNode nodeBefore = mapper.valueToTree(nodes.findById(id(1)).orElseThrow());
        entityManager.clear();
        ReplaySqlInspector.begin();
        list(0, 50);
        detail(runId, id(1));
        mockMvc.perform(get("/api/v1/runs/{runId}", runId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.runId").value(runId.toString()))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.steps").value(3));
        List<String> statements = ReplaySqlInspector.finish();
        assertThat(statements).isNotEmpty().allSatisfy(sql -> assertThat(sql).startsWith("select "));
        entityManager.flush();
        entityManager.clear();
        assertThat(mapper.<JsonNode>valueToTree(runs.findById(runId).orElseThrow())).isEqualTo(runBefore);
        assertThat(mapper.<JsonNode>valueToTree(nodes.findById(id(1)).orElseThrow())).isEqualTo(nodeBefore);
    }

    private WorkflowRunEntity saveRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(UUID.randomUUID());
        run.setWorkflowId(UUID.randomUUID());
        run.setWorkflowVersion(1);
        run.setStatus(RunStatus.SUCCEEDED);
        run.setCreatedAt(BASE);
        run.setStartedAt(BASE);
        run.setFinishedAt(BASE.plusSeconds(5));
        run.setSteps(3);
        return runs.saveAndFlush(run);
    }

    private NodeRunEntity saveNode(UUID owner, int number, String nodeId, int attempt,
                                   NodeRunStatus state, Instant start, Instant end) {
        NodeRunEntity node = new NodeRunEntity();
        node.setId(id(number));
        node.setRunId(owner);
        node.setNodeId(nodeId);
        node.setNodeType("llm.chat");
        node.setStatus(state);
        node.setAttempt(attempt);
        node.setStartedAt(start);
        node.setEndedAt(end);
        node.setInput(mapper.createObjectNode().put("text", "synthetic-input"));
        node.setOutput(mapper.createObjectNode().put("text", "synthetic-output"));
        node.setError("synthetic-error");
        return nodes.saveAndFlush(node);
    }

    private static UUID id(int number) { return new UUID(0, number); }

    private JsonNode list(int page, int size) throws Exception {
        String json = mockMvc.perform(get("/api/v1/runs/{runId}/nodes", runId)
                        .param("page", Integer.toString(page)).param("size", Integer.toString(size)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data");
    }

    private JsonNode detail(UUID owner, UUID nodeId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/runs/{runId}/nodes/{nodeRunId}", owner, nodeId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data");
    }

    private static List<String> ids(JsonNode data) {
        List<String> ids = new ArrayList<>();
        data.path("items").forEach(node -> ids.add(node.path("nodeRunId").asText()));
        return ids;
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @TestConfiguration
    static class SqlInspectionConfiguration {
        @Bean
        HibernatePropertiesCustomizer replaySqlInspector() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", new ReplaySqlInspector());
        }
    }

    /** Test-thread-only SQL shapes; Hibernate bind values and snapshot bodies are never captured. */
    static class ReplaySqlInspector implements StatementInspector {
        private static final ThreadLocal<List<String>> CAPTURED = new ThreadLocal<>();
        static void begin() { CAPTURED.set(new ArrayList<>()); }
        static List<String> finish() {
            List<String> statements = CAPTURED.get();
            CAPTURED.remove();
            return statements == null ? List.of() : List.copyOf(statements);
        }
        @Override public String inspect(String sql) {
            List<String> statements = CAPTURED.get();
            if (statements != null) statements.add(sql.toLowerCase(Locale.ROOT));
            return sql;
        }
    }
}
