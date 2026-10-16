package com.aiflow.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 能力档位接口（离线档）。测试 profile 里只有 mock provider + mock embedding，
 * 所以这里固定是 L3：要让「降级对用户可见」这件事可回归，先把它在最低档下的输出钉死。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CapabilityApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void reportsOfflineFallbackLevelWithMockTiers() throws Exception {
        JsonNode data = getData("/api/v1/capabilities");

        assertThat(data.path("level").asText()).isEqualTo("L3");
        assertThat(data.path("levelName").asText()).contains("预设话术");
        assertThat(data.path("notice").asText()).contains("mock-embedding");

        JsonNode chat = channel(data, "chat");
        assertThat(chat.path("status").asText()).isEqualTo("ok");
        assertThat(chat.path("activeProvider").asText()).isEqualTo("mock");
        assertThat(chat.path("activeTier").asText()).isEqualTo("mock");
        assertThat(chat.path("candidates")).hasSize(1);
        assertThat(chat.path("candidates").get(0).path("detail").asText()).contains("模拟");

        JsonNode vision = channel(data, "vision");
        assertThat(vision.path("status").asText()).isEqualTo("ok");
        assertThat(vision.path("activeProvider").asText()).isEqualTo("mock");
        assertThat(vision.path("candidates")).hasSize(1);
        assertThat(data.path("notice").asText()).contains("多模态当前由 mock 承接");

        JsonNode embedding = channel(data, "embedding");
        assertThat(embedding.path("activeProvider").asText()).isEqualTo("mock-embedding");

        JsonNode cloudKb = channel(data, "cloud-knowledge-base");
        assertThat(cloudKb.path("candidates").get(0).path("detail").asText()).contains("未启用");

        JsonNode asr = channel(data, "asr");
        assertThat(asr.path("status").asText()).isEqualTo("unavailable");
        assertThat(asr.path("activeProvider").isNull()).isTrue();
        assertThat(asr.path("candidates").get(0).path("detail").asText())
                .contains("aiflow.audio.enabled=false");

        JsonNode tts = channel(data, "tts");
        assertThat(tts.path("status").asText()).isEqualTo("unavailable");
        assertThat(tts.path("candidates").get(0).path("detail").asText())
                .contains("aiflow.audio.enabled=false");
    }

    private JsonNode channel(JsonNode data, String name) {
        for (JsonNode channel : data.path("channels")) {
            if (name.equals(channel.path("channel").asText())) {
                return channel;
            }
        }
        throw new AssertionError("能力报告里没有 " + name + " 通道: " + data);
    }

    private JsonNode getData(String path) throws Exception {
        String body = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }
}
