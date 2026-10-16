package com.aiflow.engine.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.aiflow.engine.api.AudioApiTestSupport.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = {
        "aiflow.audio.enabled=true", "aiflow.audio.mode=mock",
        "aiflow.audio.asr.enabled=false", "aiflow.audio.tts.enabled=false"
})
class AudioChannelsDisabledApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void disabledAsrChannelCannotBeCalledUnderEnabledRoot() throws Exception {
        mvc.perform(multipart(TRANSCRIPTIONS).file(new MockMultipartFile("file", "sample.wav", "audio/wav", shortWav())))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300));
    }

    @Test
    void disabledTtsChannelCannotBeCalledUnderEnabledRoot() throws Exception {
        mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav").content("{\"text\":\"sample\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
