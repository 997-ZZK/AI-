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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 图片问答端到端（测试 profile 里唯一的档位是 mock，它声明了 vision）：
 * 上传 -> 建会话绑定 vision-chat -> 带 attachmentIds 发消息 -> 轮询 run -> 输出图片数量与降级留痕。
 *
 * 这条用例真正锁住的是「图片引用从 HTTP 请求一路传到节点变量」：中间任何一环丢了 attachments，
 * 节点都会以「没有可用的图片引用」失败，而不是悄悄拿纯文本问模型。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VisionFlowIntegrationTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sessionMessageWithAttachmentRunsVisionWorkflow() throws Exception {
        String attachmentId = uploadPng();
        UUID userId = UUID.fromString(postData("/api/v1/users",
                Map.of("externalId", "vision-it-" + UUID.randomUUID(), "displayName", "Vision IT"))
                .path("id").asText());
        UUID sessionId = UUID.fromString(postData("/api/v1/sessions",
                        Map.of("userId", userId.toString(), "title", "vision 集成", "workflowName", "vision-chat"))
                .path("id").asText());

        JsonNode accepted = postData("/api/v1/sessions/" + sessionId + "/messages", Map.of(
                "clientMsgId", "vision-it-" + UUID.randomUUID(),
                "content", "图里有什么？",
                "attachmentIds", List.of(attachmentId)));
        JsonNode run = awaitRun(UUID.fromString(accepted.path("runId").asText()));

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode output = run.path("output");
        assertThat(output.path("reply").asText()).contains("mock 视觉").contains("1 张图片");
        assertThat(output.path("imageCount").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsMessageThatReferencesAnUnknownAttachment() throws Exception {
        UUID userId = UUID.fromString(postData("/api/v1/users",
                Map.of("externalId", "vision-it-" + UUID.randomUUID(), "displayName", "Vision IT"))
                .path("id").asText());
        UUID sessionId = UUID.fromString(postData("/api/v1/sessions",
                        Map.of("userId", userId.toString(), "workflowName", "vision-chat"))
                .path("id").asText());

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientMsgId", "vision-missing-" + UUID.randomUUID(),
                                "content", "图里有什么？",
                                "attachmentIds", List.of(UUID.randomUUID().toString())))))
                .andExpect(status().isNotFound());
    }

    private String uploadPng() throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "cat.png", "image/png", PNG)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data").path("attachmentId").asText();
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