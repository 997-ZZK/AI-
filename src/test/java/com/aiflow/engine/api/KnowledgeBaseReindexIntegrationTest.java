package com.aiflow.engine.api;

import com.aiflow.engine.retrieval.EmbeddingStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 索引与 embedding provider 绑定的一致性（H2 + 内存向量库 + mock embedding，全程离线）。
 *
 * <p>覆盖三件事：索引是别的模型建的时候检索要**显式失败**而不是返回空结果；
 * reindex 能把索引换成当前 provider；修好之后检索恢复。
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeBaseReindexIntegrationTest {

    private static final String KB_CONTENT = """
            # 报销制度

            ## 发票要求
            发票抬头必须为公司全称，并注明税号。
            """;
    private static final String QUERY = "发票抬头必须为公司全称，并注明税号";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddingStore embeddingStore;

    @Test
    void retrievalFailsLoudlyWhenIndexBelongsToAnotherModel() throws Exception {
        String kbId = newKbId();
        embeddingStore.upsertAll(kbId, "legacy-embedding", Map.of(UUID.randomUUID().toString(), new float[1024]));

        mockMvc.perform(post("/api/v1/kbs/" + kbId + "/retrieve")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("query", QUERY, "topK", 5, "sources", List.of("local")))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50300))
                .andExpect(jsonPath("$.message").value(containsString("legacy-embedding")))
                .andExpect(jsonPath("$.message").value(containsString("/reindex")));
    }

    @Test
    void reindexRepairsIndexBuiltByAnotherModel() throws Exception {
        String kbId = newKbId();
        String documentId = postData("/api/v1/kbs/" + kbId + "/documents",
                Map.of("title", "报销制度.md", "content", KB_CONTENT, "sourceType", "MARKDOWN"))
                .path("documentId").asText();
        JsonNode chunks = getData("/api/v1/kbs/" + kbId + "/documents/" + documentId + "/chunks");
        assertThat(chunks).isNotEmpty();
        List<String> chunkIds = new ArrayList<>();
        chunks.forEach(chunk -> chunkIds.add(chunk.path("chunkId").asText()));

        // 造出「索引是换 provider 之前建的」状态：当前模型的向量没了，只剩别的模型的
        embeddingStore.removeAll(chunkIds);
        embeddingStore.upsertAll(kbId, "legacy-embedding", Map.of(chunkIds.get(0), new float[1024]));

        mockMvc.perform(post("/api/v1/kbs/" + kbId + "/retrieve")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("query", QUERY, "topK", 5, "sources", List.of("local")))))
                .andExpect(status().isServiceUnavailable());

        JsonNode reindexed = postData("/api/v1/kbs/" + kbId + "/reindex", Map.of());
        assertThat(reindexed.path("chunks").asInt()).isEqualTo(chunkIds.size());
        assertThat(reindexed.path("documents").asInt()).isEqualTo(1);
        assertThat(reindexed.path("embeddingModel").asText()).isEqualTo("mock-embedding");
        assertThat(reindexed.path("previousModels").has("legacy-embedding")).isTrue();

        JsonNode retrieved = postData("/api/v1/kbs/" + kbId + "/retrieve",
                Map.of("query", QUERY, "topK", 5, "sources", List.of("local")));
        assertThat(retrieved.path("chunks")).isNotEmpty();
        assertThat(embeddingStore.load(kbId, "mock-embedding")).hasSize(chunkIds.size());
    }

    @Test
    void reindexOnEmptyKnowledgeBaseReportsZeroWithoutFailing() throws Exception {
        JsonNode result = postData("/api/v1/kbs/" + newKbId() + "/reindex", Map.of());

        assertThat(result.path("chunks").asInt()).isZero();
        assertThat(result.path("documents").asInt()).isZero();
        assertThat(result.path("embeddingModel").asText()).isEqualTo("mock-embedding");
    }

    private String newKbId() {
        return "reindex-kb-" + UUID.randomUUID();
    }

    private JsonNode getData(String path) throws Exception {
        return unwrap(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postData(String path, Map<String, Object> body) throws Exception {
        return unwrap(mockMvc.perform(post(path)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode unwrap(String body) throws Exception {
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }
}