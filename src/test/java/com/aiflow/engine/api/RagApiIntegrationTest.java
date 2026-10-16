package com.aiflow.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG API 集成测试（H2 + memory 向量库 + mock embedding / mock 模型，全程离线）。
 *
 * 覆盖：文本入库 -> 重复检测 -> 文档/切片查询 -> 检索调试端点 -> 文件上传与不支持类型拒绝 ->
 * rag-chat 工作流端到端（建会话绑定工作流 -> 发消息 -> 轮询 run -> 输出带引用）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RagApiIntegrationTest {

    private static final String KB_CONTENT = """
            # 报销制度

            ## 发票要求
            发票抬头必须为公司全称，并注明税号，电子发票需要打印报销封面。

            ## 差旅标准
            一线城市住宿标准每晚五百元，二线城市每晚三百五十元。
            """;
    private static final String KB_QUERY = "发票抬头必须为公司全称，并注明税号";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void ingestListAndRetrieveRoundTrip() throws Exception {
        String kbId = newKbId();
        JsonNode ingested = ingestText(kbId, "报销制度.md", KB_CONTENT);

        assertThat(ingested.path("chunkCount").asInt()).isGreaterThan(0);
        assertThat(ingested.path("duplicate").asBoolean()).isFalse();
        assertThat(ingested.path("status").asText()).isEqualTo("INDEXED");

        JsonNode documents = getData("/api/v1/kbs/" + kbId + "/documents");
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).path("title").asText()).isEqualTo("报销制度.md");

        String documentId = ingested.path("documentId").asText();
        JsonNode chunks = getData("/api/v1/kbs/" + kbId + "/documents/" + documentId + "/chunks");
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).path("contentHash").isMissingNode()).isTrue();
        assertThat(chunks.get(0).path("preview").asText()).contains("发票抬头");

        JsonNode retrieved = postData("/api/v1/kbs/" + kbId + "/retrieve",
                Map.of("query", KB_QUERY, "topK", 5, "includeContext", true));
        assertThat(retrieved.path("chunks")).isNotEmpty();
        assertThat(retrieved.path("citations")).isNotEmpty();
        assertThat(retrieved.path("stats").path("raw").asInt()).isGreaterThan(0);
        assertThat(retrieved.path("contextBlock").asText()).contains("<<<资料1>>>");
    }

    @Test
    void reIngestingSameContentIsReportedAsDuplicate() throws Exception {
        String kbId = newKbId();
        String firstId = ingestText(kbId, "制度.md", KB_CONTENT).path("documentId").asText();

        JsonNode second = ingestText(kbId, "制度副本.md", KB_CONTENT);

        assertThat(second.path("duplicate").asBoolean()).isTrue();
        assertThat(second.path("documentId").asText()).isEqualTo(firstId);
        assertThat(getData("/api/v1/kbs/" + kbId + "/documents")).hasSize(1);
    }

    @Test
    void uploadsMarkdownFileAndRejectsUnsupportedType() throws Exception {
        String kbId = newKbId();
        MockMultipartFile markdown = new MockMultipartFile("file", "policy.md", "text/markdown",
                KB_CONTENT.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/kbs/" + kbId + "/documents/upload").file(markdown))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("\"chunkCount\""));

        MockMultipartFile pdf = new MockMultipartFile("file", "policy.pdf", "application/pdf",
                "%PDF-1.4".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/kbs/" + kbId + "/documents/upload").file(pdf))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ragChatRunProducesReplyWithCitations() throws Exception {
        String kbId = "demo";
        ingestText(kbId, "报销制度.md", KB_CONTENT);
        UUID userId = UUID.fromString(postData("/api/v1/users",
                Map.of("externalId", "rag-it-" + UUID.randomUUID(), "displayName", "RAG IT"))
                .path("id").asText());
        JsonNode session = postData("/api/v1/sessions",
                Map.of("userId", userId.toString(), "title", "rag-chat 集成", "workflowName", "rag-chat"));
        assertThat(session.path("workflowId").asText()).isNotBlank();
        UUID sessionId = UUID.fromString(session.path("id").asText());

        JsonNode accepted = postData("/api/v1/sessions/" + sessionId + "/messages",
                Map.of("clientMsgId", "it-" + UUID.randomUUID(), "content", KB_QUERY));
        JsonNode run = awaitRun(UUID.fromString(accepted.path("runId").asText()));

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode output = run.path("output");
        assertThat(output.path("reply").asText()).isNotBlank().contains("已收到");
        assertThat(output.path("citations")).isNotEmpty();
        assertThat(run.path("steps").asInt()).isGreaterThanOrEqualTo(3);
    }

    private String newKbId() {
        return "it-kb-" + UUID.randomUUID();
    }

    private JsonNode ingestText(String kbId, String title, String content) throws Exception {
        return postData("/api/v1/kbs/" + kbId + "/documents",
                Map.of("title", title, "content", content, "sourceType", "MARKDOWN"));
    }

    private JsonNode awaitRun(UUID runId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode run = null;
        while (System.currentTimeMillis() < deadline) {
            run = getData("/api/v1/runs/" + runId);
            String runStatus = run.path("status").asText();
            if (!"PENDING".equals(runStatus) && !"RUNNING".equals(runStatus)) {
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("运行未在 20s 内结束: " + run);
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