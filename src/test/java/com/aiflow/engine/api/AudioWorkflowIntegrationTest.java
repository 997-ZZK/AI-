package com.aiflow.engine.api;

import com.aiflow.engine.audio.AudioHandleException;
import com.aiflow.engine.audio.AudioHandleStore;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.graph.EdgeDef;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.RetryPolicy;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.entity.NodeRunEntity;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.NodeRunRepository;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The existing text entry point runs real audio nodes without persisting an audio body. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = {
        "aiflow.audio.enabled=true", "aiflow.audio.mode=mock",
        "aiflow.audio.asr.enabled=true", "aiflow.audio.tts.enabled=true"
})
class AudioWorkflowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private NodeRunRepository nodeRuns;
    @Autowired private WorkflowRunRepository workflowRuns;
    @Autowired private MessageRepository messages;
    @Autowired private AudioHandleStore handles;
    @Autowired private RunEventBus eventBus;

    @Test
    void registeredRoundTripPersistsOnlyTextHandleAndMetadata() throws Exception {
        String workflowName = "audio-it-" + UUID.randomUUID();
        WorkflowDefinition definition = roundTrip(workflowName);
        postData("/api/v1/workflows", definition);
        UUID userId = UUID.fromString(postData("/api/v1/users", Map.of(
                "externalId", "audio-it-" + UUID.randomUUID(), "displayName", "Audio IT")).path("id").asText());
        UUID sessionId = UUID.fromString(postData("/api/v1/sessions", Map.of(
                "userId", userId.toString(), "workflowName", workflowName)).path("id").asText());
        String text = "用于工作流集成验证的合成文本";
        UUID runId = UUID.fromString(postData("/api/v1/sessions/" + sessionId + "/messages", Map.of(
                "clientMsgId", "audio-it-" + UUID.randomUUID(), "content", text)).path("runId").asText());

        JsonNode run = awaitRun(runId);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("output").size()).isEqualTo(1);
        assertThat(run.path("output").path("reply").asText()).contains("mock ASR");

        List<NodeRunEntity> rows = nodeRuns.findByRunIdOrderByStartedAtAsc(runId);
        assertThat(rows).extracting(NodeRunEntity::getNodeType)
                .containsExactly("start", "tts.speak", "asr.transcribe", "end");
        assertThat(rows).extracting(NodeRunEntity::getStatus).containsOnly(NodeRunStatus.SUCCEEDED);
        NodeRunEntity tts = rows.get(1);
        NodeRunEntity asr = rows.get(2);
        String handle = tts.getOutput().path("audioHandle").asText();
        assertThat(handle).isNotBlank();
        assertThat(UUID.fromString(handle)).isNotNull();
        assertThat(tts.getOutput().path("provider").asText()).isEqualTo("mock");
        assertThat(tts.getOutput().path("model").asText()).isEqualTo("mock-tts-1");
        assertThat(tts.getOutput().path("byteLength").asLong()).isPositive();
        assertThat(asr.getInput().path("audioHandle").asText()).isEqualTo(handle);
        assertThat(asr.getOutput().path("transcript").asText()).isEqualTo(run.path("output").path("reply").asText());
        assertThat(asr.getOutput().path("model").asText()).isEqualTo("mock-asr-1");
        for (NodeRunEntity row : rows) {
            assertSafeJson(row.getInput());
            assertSafeJson(row.getOutput());
        }
        WorkflowRunEntity persistedRun = workflowRuns.findById(runId).orElseThrow();
        assertThat(persistedRun.getInput().properties()).hasSize(1);
        assertThat(persistedRun.getInput().path("input").asText()).isEqualTo(text);
        assertSafeJson(persistedRun.getInput());
        assertSafeJson(persistedRun.getOutput());

        // Subscribe with the real id after the run was accepted and replay the in-memory event buffer.
        List<RunEvent> replayed = new CopyOnWriteArrayList<>();
        RunEventBus.Subscription replaySubscription = eventBus.subscribe(runId.toString(), 0, replayed::add);
        replaySubscription.close();
        for (RunEvent event : replayed) {
            assertThat(event.data().toString()).doesNotContain("RIFF", "UklGR", "audioBytes", "base64", "TtsResponse[");
        }

        // finishRun commits just before the execution segment's finally releases resources.
        awaitResourcesClosed(runId);
        assertThatThrownBy(() -> handles.access(runId, () -> { }))
                .isInstanceOf(AudioHandleException.class);

        MessageEntity user = messages.findFirstByRunIdAndRole(runId, "USER").orElseThrow();
        MessageEntity assistant = awaitAssistant(runId);
        assertThat(user.getContentType()).isEqualTo("TEXT");
        assertThat(user.getContent()).isEqualTo(text);
        assertThat(assistant.getContentType()).isEqualTo("TEXT");
        assertThat(assistant.getContent()).isEqualTo(run.path("output").path("reply").asText());
        assertThat(user.getContent()).doesNotContain(handle, "RIFF", "UklGR", "data:audio");
        assertThat(assistant.getContent()).doesNotContain(handle, "RIFF", "UklGR", "data:audio");
        assertThat(messages.countBySessionId(sessionId)).isEqualTo(2L);
    }

    @Test
    void registrationRejectsNonStringUnknownAndUnsupportedAudioParameters() throws Exception {
        List<NodeDef> invalid = List.of(
                node("tts", "tts.speak", Map.of("text", 123)),
                node("tts", "tts.speak", Map.of("voice", Map.of("value", "speaker"))),
                node("tts", "tts.speak", Map.of("format", "mp3")),
                node("tts", "tts.speak", Map.of("audioBytes", "audio-body-marker-d2")),
                node("asr", "asr.transcribe", Map.of("audioHandle", 123)),
                node("asr", "asr.transcribe", Map.of("url", "audio-body-marker-d2")));
        for (NodeDef badNode : invalid) {
            String body = mvc.perform(post("/api/v1/workflows").contentType("application/json")
                            .content(mapper.writeValueAsString(new WorkflowDefinition(null,
                                    "audio-invalid-" + UUID.randomUUID(), 1, List.of(badNode), List.of()))))
                    .andExpect(status().isUnprocessableEntity()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain("audio-body-marker-d2");
        }
    }

    private WorkflowDefinition roundTrip(String name) {
        return new WorkflowDefinition(null, name, 1,
                List.of(node("start", "start", Map.of()), node("tts", "tts.speak", Map.of()),
                        node("asr", "asr.transcribe", Map.of()),
                        node("end", "end", Map.of("outputs", Map.of("reply", "transcript")))),
                List.of(new EdgeDef("start", "tts", null), new EdgeDef("tts", "asr", null),
                        new EdgeDef("asr", "end", null)));
    }

    private NodeDef node(String id, String type, Map<String, Object> params) {
        return new NodeDef(id, type, id, params, Set.of(), new RetryPolicy(1, 0), null);
    }

    private void assertSafeJson(JsonNode value) {
        if (value == null || value.isNull()) return;
        assertThat(value.isBinary()).as("audio bytes must never become JSON binary nodes").isFalse();
        if (value.isTextual()) {
            assertThat(value.asText()).doesNotContain("RIFF", "UklGR", "data:audio", "AudioInput[", "TtsResponse[");
        } else if (value.isContainerNode()) {
            value.forEach(this::assertSafeJson);
        }
        if (value.isObject()) {
            value.fieldNames().forEachRemaining(key -> assertThat(key)
                    .doesNotMatch("(?i)^(audioBytes|audioBody|audio|base64|ttsResponse|asrResponse)$"));
        }
    }

    private JsonNode awaitRun(UUID runId) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        JsonNode run = null;
        while (System.nanoTime() < deadline) {
            run = getData("/api/v1/runs/" + runId);
            if (!Set.of("PENDING", "RUNNING").contains(run.path("status").asText())) return run;
            Thread.sleep(25);
        }
        throw new AssertionError("Audio workflow did not terminate within 20 seconds: " + run);
    }

    private void awaitResourcesClosed(UUID runId) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                handles.access(runId, () -> { });
            } catch (AudioHandleException closed) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Audio workflow resources were not closed");
    }

    private MessageEntity awaitAssistant(UUID runId) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            var result = messages.findFirstByRunIdAndRole(runId, "ASSISTANT");
            if (result.isPresent()) return result.get();
            Thread.sleep(10);
        }
        throw new AssertionError("Audio workflow did not persist its text assistant message");
    }

    private JsonNode getData(String path) throws Exception {
        return unwrap(mvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postData(String path, Object body) throws Exception {
        return unwrap(mvc.perform(post(path).contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode unwrap(String body) throws Exception {
        JsonNode json = mapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }
}
