package com.aiflow.engine.api;

import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.TtsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D0 能力报告：开启 mock 时语音通道明确标注 mock，不冒充真实厂商。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "aiflow.audio.enabled=true",
        "aiflow.audio.mode=mock",
        "aiflow.audio.asr.enabled=true",
        "aiflow.audio.tts.enabled=true"
})
class AudioCapabilityApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AsrProvider asrProvider;

    @Autowired
    private TtsProvider ttsProvider;

    @Test
    void reportsMockAudioChannelsWithoutClaimingRealAvailability() throws Exception {
        assertThat(asrProvider.id()).isEqualTo("mock");
        assertThat(ttsProvider.id()).isEqualTo("mock");
        JsonNode data = getData();

        JsonNode asr = channel(data, "asr");
        assertThat(asr.path("status").asText()).isEqualTo("mock");
        assertThat(asr.path("activeProvider").asText()).isEqualTo("mock");
        assertThat(asr.path("activeTier").asText()).isEqualTo("mock");
        assertThat(asr.path("candidates").get(0).path("detail").asText())
                .contains("仅用于联调").doesNotContain("AIFLOW_AUDIO_ASR_API_KEY=");

        JsonNode tts = channel(data, "tts");
        assertThat(tts.path("status").asText()).isEqualTo("mock");
        assertThat(tts.path("activeProvider").asText()).isEqualTo("mock");
        assertThat(tts.path("candidates").get(0).path("detail").asText())
                .contains("仅用于联调").doesNotContain("AIFLOW_AUDIO_TTS_API_KEY=");
    }

    private JsonNode getData() throws Exception {
        String body = mockMvc.perform(get("/api/v1/capabilities")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }

    private JsonNode channel(JsonNode data, String name) {
        for (JsonNode channel : data.path("channels")) {
            if (name.equals(channel.path("channel").asText())) {
                return channel;
            }
        }
        throw new AssertionError("能力报告里没有 " + name + " 通道: " + data);
    }
}
