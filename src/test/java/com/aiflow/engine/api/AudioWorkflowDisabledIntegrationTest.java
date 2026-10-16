package com.aiflow.engine.api;

import com.aiflow.engine.graph.EdgeDef;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.RetryPolicy;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Audio nodes remain part of the graph vocabulary while the audio capability is off by default. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AudioWorkflowDisabledIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @Test
    void disabledAudioNodeIsRegisteredButUsesTextFallbackWithoutAudioPayload() throws Exception {
        String workflowName = "audio-disabled-it-" + UUID.randomUUID();
        WorkflowDefinition definition = new WorkflowDefinition(null, workflowName, 1,
                List.of(node("start", "start", Map.of()),
                        node("tts", "tts.speak", Map.of("fallback", "语音能力当前未启用")),
                        node("end", "end", Map.of("outputs", Map.of("reply", "fallback")))),
                List.of(new EdgeDef("start", "tts", null), new EdgeDef("tts", "end", null)));
        JsonNode registered = postData("/api/v1/workflows", definition);
        assertThat(registered.path("name").asText()).isEqualTo(workflowName);
        UUID userId = UUID.fromString(postData("/api/v1/users", Map.of(
                "externalId", "audio-disabled-" + UUID.randomUUID())).path("id").asText());
        UUID sessionId = UUID.fromString(postData("/api/v1/sessions", Map.of(
                "userId", userId.toString(), "workflowName", workflowName)).path("id").asText());
        UUID runId = UUID.fromString(postData("/api/v1/sessions/" + sessionId + "/messages", Map.of(
                "clientMsgId", "audio-disabled-" + UUID.randomUUID(), "content", "text")).path("runId").asText());
        JsonNode run = awaitRun(runId);
        assertThat(run.path("status").asText()).isEqualTo("DEGRADED");
        assertThat(run.path("output").path("reply").asText()).isEqualTo("语音能力当前未启用");
        assertThat(run.toString()).doesNotContain("RIFF", "UklGR", "base64", "audioBytes");
    }

    private NodeDef node(String id, String type, Map<String, Object> params) {
        return new NodeDef(id, type, id, params, Set.of(), new RetryPolicy(1, 0), null);
    }

    private JsonNode awaitRun(UUID runId) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        JsonNode run = null;
        while (System.nanoTime() < deadline) {
            run = getData("/api/v1/runs/" + runId);
            if (!Set.of("PENDING", "RUNNING").contains(run.path("status").asText())) return run;
            Thread.sleep(25);
        }
        throw new AssertionError("Disabled audio workflow did not terminate within 20 seconds: " + run);
    }

    private JsonNode getData(String path) throws Exception {
        return unwrap(mvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postData(String path, Object body) throws Exception {
        return unwrap(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode unwrap(String body) throws Exception {
        JsonNode json = mapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }
}
