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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件接口：上传 -> 类型/大小把关 -> 读回。
 *
 * 覆盖三件事：真实 png 能存能取、伪装成图片的文本被拒、不存在的 id 给 404 而不是 500。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentApiIntegrationTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void uploadsPngAndServesContentBack() throws Exception {
        JsonNode data = upload("cat.png", PNG, "image/png");

        assertThat(data.path("mimeType").asText()).isEqualTo("image/png");
        assertThat(data.path("sizeBytes").asInt()).isEqualTo(PNG.length);
        assertThat(data.path("sha256").asText()).hasSize(64);
        assertThat(data.path("url").asText()).isEqualTo("/api/v1/attachments/" + data.path("attachmentId").asText());

        byte[] served = mockMvc.perform(get(data.path("url").asText()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(PNG);
    }

    @Test
    void rejectsContentThatIsNotAnImage() throws Exception {
        mockMvc.perform(multipart("/api/v1/attachments").file(new MockMultipartFile("file", "evil.png",
                        "image/png", "这不是图片".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("按文件头识别"));
    }

    @Test
    void unknownAttachmentIdIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/attachments/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    private JsonNode upload(String fileName, byte[] bytes, String contentType) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", fileName, contentType, bytes)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }
}