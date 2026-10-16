package com.aiflow.engine.api;

import com.aiflow.engine.memory.longterm.MemoryKind;
import com.aiflow.engine.memory.longterm.MemorySource;
import com.aiflow.engine.persistence.entity.SessionEntity;
import com.aiflow.engine.persistence.entity.UserEntity;
import com.aiflow.engine.persistence.entity.UserMemoryEntity;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.persistence.repo.SessionRepository;
import com.aiflow.engine.persistence.repo.UserMemoryRepository;
import com.aiflow.engine.persistence.repo.UserProfileRepository;
import com.aiflow.engine.persistence.repo.UserRepository;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.run.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProfileIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired UserMemoryRepository memories;
    @Autowired WorkflowRunRepository runs;
    @Autowired SessionRepository sessions;
    private UUID userId;

    @BeforeEach
    void createUser() {
        userId = UUID.randomUUID();
        users.save(new UserEntity(userId, "profile-" + UUID.randomUUID(), "合成画像用户", Instant.now()));
    }

    @Test
    void queryingDefaultAndPreviewDoesNotCreateOrEnableAProfile() throws Exception {
        JsonNode view = read(userId);
        assertThat(view.path("aggregationEnabled").asBoolean()).isFalse();
        assertThat(view.path("timezone").asText()).isEqualTo("UTC");
        assertThat(view.path("version").asLong()).isZero();
        assertThat(view.path("statistics").path("runCount").asLong()).isZero();
        JsonNode preview = data(mvc.perform(get(path(userId) + "/preview"))
                .andExpect(status().isOk()).andReturn());
        assertThat(preview.path("profileEnabled").asBoolean()).isFalse();
        assertThat(profiles.existsById(userId)).isFalse();
    }

    @Test
    void unknownUserIs404ForEveryProfileEndpoint() throws Exception {
        UUID absent = UUID.randomUUID();
        mvc.perform(get(path(absent))).andExpect(status().isNotFound());
        mvc.perform(get(path(absent) + "/preview")).andExpect(status().isNotFound());
        mvc.perform(put(path(absent)).contentType(MediaType.APPLICATION_JSON)
                .content(body(true, "UTC", 0))).andExpect(status().isNotFound());
        mvc.perform(delete(path(absent)).param("expectedVersion", "0")).andExpect(status().isNotFound());
        assertThat(profiles.existsById(absent)).isFalse();
    }

    @Test
    void malformedInputCannotCreateAProfile() throws Exception {
        mvc.perform(put(path(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(body(true, "NoSuch/Zone", 0))).andExpect(status().isBadRequest());
        mvc.perform(put(path(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(body(true, "UTC", -1))).andExpect(status().isBadRequest());
        mvc.perform(put(path(userId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"aggregationEnabled\":true,\"timezone\":\"UTC\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(path(userId))).andExpect(status().isBadRequest());
        mvc.perform(delete(path(userId)).param("expectedVersion", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(path(userId) + "/preview").param("profilePreference", "typo"))
                .andExpect(status().isBadRequest());
        assertThat(profiles.existsById(userId)).isFalse();
    }

    @Test
    void staleUpdateAndResetLeaveTheCurrentStateIntact() throws Exception {
        JsonNode enabled = update(true, "Asia/Shanghai", 0);
        assertThat(enabled.path("version").asLong()).isEqualTo(1);
        mvc.perform(put(path(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(body(false, "UTC", 0))).andExpect(status().isConflict());
        mvc.perform(delete(path(userId)).param("expectedVersion", "0")).andExpect(status().isConflict());
        JsonNode current = read(userId);
        assertThat(current.path("aggregationEnabled").asBoolean()).isTrue();
        assertThat(current.path("timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(current.path("version").asLong()).isEqualTo(1);
    }

    @Test
    void timezoneEditPreservesStartButResetAndReenableExcludeOldRuns() throws Exception {
        JsonNode enabled = update(true, "UTC", 0);
        // Use the persisted boundary to avoid wall-clock/DB precision races and verify inclusive counting.
        Instant persistedSince = profiles.findById(userId).orElseThrow().getStatisticsSince();
        WorkflowRunEntity run = run(persistedSince, RunStatus.WAITING_APPROVAL);
        Instant persistedRunAt = runs.findById(run.getId()).orElseThrow().getCreatedAt();
        assertThat(persistedRunAt).isEqualTo(persistedSince);
        JsonNode first = read(userId);
        assertThat(persistedRunAt).isBetween(Instant.parse(first.path("windowFrom").asText()),
                Instant.parse(first.path("windowTo").asText()));
        assertThat(first.path("statistics").path("runCount").asLong()).isEqualTo(1);
        assertThat(first.path("statistics").path("statuses").path("WAITING_APPROVAL").asLong()).isEqualTo(1);
        run.setStartedAt(Instant.now());
        run.setStatus(RunStatus.SUCCEEDED);
        runs.saveAndFlush(run);
        assertThat(read(userId).path("statistics").path("runCount").asLong()).isEqualTo(1);
        JsonNode changed = update(true, "Asia/Shanghai", 1);
        // H2/PostgreSQL timestamp precision may normalize trailing nanoseconds; the instant is the same floor.
        Instant changedSince = Instant.parse(changed.path("statisticsSince").asText());
        Instant enabledSince = Instant.parse(enabled.path("statisticsSince").asText());
        assertThat(Math.abs(java.time.Duration.between(changedSince, enabledSince).toNanos()))
                .isLessThanOrEqualTo(1_000L);
        UserMemoryEntity preference = memory("response-style", "concise", MemorySource.MANUAL);
        JsonNode reset = data(mvc.perform(delete(path(userId)).param("expectedVersion", "2"))
                .andExpect(status().isOk()).andReturn());
        assertThat(reset.path("aggregationEnabled").asBoolean()).isFalse();
        assertThat(reset.path("statistics").path("runCount").asLong()).isZero();
        assertThat(runs.existsById(run.getId())).isTrue();
        assertThat(memories.existsById(preference.getId())).isTrue();
        assertThat(read(userId).path("version").asLong()).isEqualTo(3);
        JsonNode reenabled = update(true, "UTC", 3);
        assertThat(Instant.parse(reenabled.path("statisticsSince").asText()))
                .isAfterOrEqualTo(Instant.parse(reset.path("statisticsSince").asText()));
        assertThat(reenabled.path("statistics").path("runCount").asLong()).isZero();
    }

    @Test
    void preferenceAndSessionPreviewShareTheAssemblyWithoutWritingHits() throws Exception {
        update(true, "UTC", 0);
        UserMemoryEntity style = memory("response-style", "detailed", MemorySource.MANUAL);
        memory("model-policy", "cheap-first", MemorySource.MANUAL);
        JsonNode decision = data(mvc.perform(get(path(userId) + "/preview"))
                .andExpect(status().isOk()).andReturn());
        assertThat(decision.path("responseStyle").asText()).isEqualTo("detailed");
        assertThat(decision.path("preferCheapTiers").asBoolean()).isTrue();
        JsonNode explicitModel = data(mvc.perform(get(path(userId) + "/preview").param("model", "fixed-model"))
                .andExpect(status().isOk()).andReturn());
        assertThat(explicitModel.path("preferCheapTiers").asBoolean()).isFalse();
        JsonNode forbidden = data(mvc.perform(get(path(userId) + "/preview").param("profilePreference", "none"))
                .andExpect(status().isOk()).andReturn());
        assertThat(forbidden.path("stylePrompt").asText()).isEmpty();
        assertThat(forbidden.path("preferCheapTiers").asBoolean()).isFalse();
        UUID sessionId = UUID.randomUUID();
        sessions.save(new SessionEntity(sessionId, userId, "画像预览", Instant.now()));
        JsonNode preview = data(mvc.perform(get("/api/v1/sessions/" + sessionId + "/memory/preview")
                .param("input", "请回答合成问题" )).andExpect(status().isOk()).andReturn());
        assertThat(preview.path("profileIncluded").asBoolean()).isTrue();
        assertThat(preview.path("historyCount").asInt()).isZero();
        assertThat(preview.path("messages").toString()).contains(decision.path("stylePrompt").asText()
                .replace("\n", "\\n"));
        assertThat(memories.findById(style.getId()).orElseThrow().getHits()).isZero();
        update(false, "UTC", 1);
        JsonNode stopped = data(mvc.perform(get(path(userId) + "/preview"))
                .andExpect(status().isOk()).andReturn());
        assertThat(stopped.path("profileEnabled").asBoolean()).isFalse();
        assertThat(stopped.path("stylePrompt").asText()).isEmpty();
        assertThat(memories.existsById(style.getId())).isTrue();
    }

    @Test
    void automaticallyExtractedPreferencesDoNotBecomeRoutingPolicies() throws Exception {
        update(true, "UTC", 0);
        memory("model-policy", "cheap-first", MemorySource.EXTRACTED);
        memory("response-style", "arbitrary-untrusted-style", MemorySource.MANUAL);
        JsonNode preview = data(mvc.perform(get(path(userId) + "/preview"))
                .andExpect(status().isOk()).andReturn());
        assertThat(preview.path("preferCheapTiers").asBoolean()).isFalse();
        assertThat(preview.path("stylePrompt").asText()).isEmpty();
        assertThat(preview.path("preferences").size()).isEqualTo(2);
    }

    @Test
    void competingChangesHaveOneWinnerForTheSameVersion() throws Exception {
        update(true, "UTC", 0);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var edit = executor.submit(() -> {
                start.await();
                return mvc.perform(put(path(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(true, "Asia/Shanghai", 1))).andReturn().getResponse().getStatus();
            });
            var reset = executor.submit(() -> {
                start.await();
                return mvc.perform(delete(path(userId)).param("expectedVersion", "1"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(edit.get(10, TimeUnit.SECONDS), reset.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(read(userId).path("version").asLong()).isEqualTo(2);
    }

    @Test
    void competingFirstEnablesCreateOneProfileWithoutAUniqueConstraintError() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var utc = executor.submit(() -> {
                start.await();
                return mvc.perform(put(path(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(true, "UTC", 0))).andReturn().getResponse().getStatus();
            });
            var shanghai = executor.submit(() -> {
                start.await();
                return mvc.perform(put(path(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(true, "Asia/Shanghai", 0))).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(utc.get(10, TimeUnit.SECONDS), shanghai.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        JsonNode current = read(userId);
        assertThat(current.path("aggregationEnabled").asBoolean()).isTrue();
        assertThat(current.path("version").asLong()).isEqualTo(1);
    }

    private String body(boolean enabled, String zone, long version) throws Exception {
        return mapper.writeValueAsString(Map.of("aggregationEnabled", enabled, "timezone", zone,
                "expectedVersion", version));
    }

    private JsonNode update(boolean enabled, String zone, long version) throws Exception {
        return data(mvc.perform(put(path(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(body(enabled, zone, version))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode read(UUID id) throws Exception {
        return data(mvc.perform(get(path(id))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static String path(UUID id) {
        return "/api/v1/users/" + id + "/profile";
    }

    private UserMemoryEntity memory(String key, String value, MemorySource source) {
        return memories.saveAndFlush(new UserMemoryEntity(UUID.randomUUID(), userId, MemoryKind.PREFERENCE,
                key, key, value, 1, source, null, null, null, Instant.now()));
    }

    private WorkflowRunEntity run(Instant createdAt, RunStatus state) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(UUID.randomUUID());
        run.setUserId(userId);
        run.setWorkflowId(UUID.randomUUID());
        run.setWorkflowVersion(1);
        run.setStatus(state);
        run.setCreatedAt(createdAt);
        run.setStartedAt(createdAt);
        return runs.saveAndFlush(run);
    }
}
