package com.aiflow.engine.api;

import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.audio.TtsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static com.aiflow.engine.api.AudioApiTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"aiflow.audio.enabled=true", "aiflow.audio.mode=mock"})
class AudioProviderFailureApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AsrProvider asr;

    @MockitoBean
    private TtsProvider tts;

    @BeforeEach
    void prepareAvailablePorts() {
        when(asr.available()).thenReturn(true);
        when(asr.id()).thenReturn("mock");
        when(tts.available()).thenReturn(true);
        when(tts.id()).thenReturn("mock");
    }

    @ParameterizedTest
    @EnumSource(AudioProviderException.Reason.class)
    void providerFailuresHaveStableStatusesAndNeverLeakCause(AudioProviderException.Reason reason,
                                                            CapturedOutput output) throws Exception {
        String marker = "vendor-response-private-marker-d1";
        when(tts.synthesize(any())).thenThrow(new AudioProviderException(reason, new IllegalStateException(marker)));
        int expectedStatus = switch (reason) {
            case UNAVAILABLE -> 503;
            case TIMEOUT -> 504;
            default -> 502;
        };
        String body = mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav")
                        .content("{\"text\":\"sample\"}"))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(expectedStatus * 100))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @Test
    void asrTimeoutIs504AndMissingOutputIs502() throws Exception {
        when(asr.transcribe(any())).thenThrow(new AudioProviderException(AudioProviderException.Reason.TIMEOUT));
        mvc.perform(multipart(TRANSCRIPTIONS).file(new MockMultipartFile("file", "sample.wav", "audio/wav", shortWav())))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value(50400));
        doReturn(null).when(asr).transcribe(any());
        mvc.perform(multipart(TRANSCRIPTIONS).file(new MockMultipartFile("file", "sample.wav", "audio/wav", shortWav())))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value(50200));
    }

    @Test
    void untypedProviderFailureIs502AndNotLoggedRaw(CapturedOutput output) throws Exception {
        String marker = "untyped-vendor-secret-d1";
        when(tts.synthesize(any())).thenThrow(new IllegalStateException(marker));
        String body = mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav")
                        .content("{\"text\":\"sample\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value(50200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @Test
    void invalidAudioOutputIs502RatherThanCaller415() throws Exception {
        when(tts.synthesize(any())).thenReturn(new TtsResponse(new byte[]{1, 2, 3}, "audio/wav", "mock", "mock-tts-1", 100));
        mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav").content("{\"text\":\"sample\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value(50200));
        when(tts.synthesize(any())).thenReturn(new TtsResponse(shortWav(), "text/html", "mock", "mock-tts-1", 100));
        mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav").content("{\"text\":\"sample\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value(50200));
    }

    @Test
    void providerMetadataCannotInjectResponseHeaders(CapturedOutput output) throws Exception {
        String marker = "injected-header-marker-d1";
        when(tts.synthesize(any())).thenReturn(new TtsResponse(shortWav(), "audio/wav", "mock",
                "mock-tts-1\r\nX-Injected: " + marker, 100));
        String body = mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav")
                        .content("{\"text\":\"sample\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value(50200))
                .andExpect(header().doesNotExist("X-Injected"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
    }
}
