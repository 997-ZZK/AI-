package com.aiflow.engine.api;

import com.aiflow.engine.audio.WavPcmValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.aiflow.engine.api.AudioApiTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "aiflow.audio.enabled=true", "aiflow.audio.mode=mock",
        "aiflow.audio.asr.enabled=true", "aiflow.audio.tts.enabled=true"
})
class AudioApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"audio/wav", "audio/x-wav", "audio/wave"})
    void transcribesSupportedMimeTypesAndReportsActualLimits(String mime) throws Exception {
        mvc.perform(multipart(TRANSCRIPTIONS).file(file(shortWav(), mime)).param("model", "caller-model"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.text").value(containsString("mock ASR")))
                .andExpect(jsonPath("$.data.provider").value("mock"))
                .andExpect(jsonPath("$.data.model").value("mock-asr-1"))
                .andExpect(jsonPath("$.data.durationMs").value(100))
                .andExpect(jsonPath("$.data.limits.maxBytes").value(5 * 1024 * 1024))
                .andExpect(jsonPath("$.data.limits.maxDurationMs").value(30_000))
                .andExpect(jsonPath("$.data.limits.sampleRate").value(16_000))
                .andExpect(jsonPath("$.data.limits.channels").value(1))
                .andExpect(jsonPath("$.data.limits.bitsPerSample").value(16));
    }

    @Test
    void returnsRawWavWithAccurateHeadersAndConfiguredModel() throws Exception {
        MvcResult result = mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav")
                        .content("{\"text\":\"你好\",\"voice\":\"example\",\"format\":\"WAV\",\"model\":\"caller-model\"}"))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/wav"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", containsString("speech.wav")))
                .andExpect(header().string("X-Audio-Provider", "mock"))
                .andExpect(header().string("X-Audio-Model", "mock-tts-1"))
                .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F');
        assertThat(result.getResponse().getHeader("Content-Length")).isEqualTo(Integer.toString(bytes.length));
        assertThat(result.getResponse().getHeader("X-Audio-Duration-Ms"))
                .isEqualTo(Long.toString(WavPcmValidator.validate(bytes).durationMs()));
    }

    @Test
    void missingPartAndEmptyAudioAreBadRequests() throws Exception {
        mvc.perform(multipart(TRANSCRIPTIONS)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        mvc.perform(multipart(TRANSCRIPTIONS).file(file(new byte[0], "audio/wav")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void rejectsMimeAndNonWavHeadersWithoutEchoingAudio(CapturedOutput output) throws Exception {
        String marker = "audio-private-marker-d1";
        mvc.perform(multipart(TRANSCRIPTIONS).file(file(shortWav(), "audio/mpeg")))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(41500));
        MvcResult result = mvc.perform(multipart(TRANSCRIPTIONS)
                        .file(file(marker.getBytes(StandardCharsets.UTF_8), "audio/wav")))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(41500)).andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @Test
    void malformedRiffStructureIsBadRequest() throws Exception {
        byte[] malformed = shortWav();
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(4, malformed.length + 20);
        mvc.perform(multipart(TRANSCRIPTIONS).file(file(malformed, "audio/wav")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void rejectsUnsupportedPcmEncodingRateAndChannels() throws Exception {
        byte[] compressed = shortWav();
        ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN).putShort(20, (short) 3);
        for (byte[] bytes : new byte[][]{compressed, wav(8_000, 1, 800), wav(16_000, 2, 1_600)}) {
            mvc.perform(multipart(TRANSCRIPTIONS).file(file(bytes, "audio/wav")))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(41500));
        }
    }

    @Test
    void enforcesThirtySecondBoundaryIncludingPartialMillisecond() throws Exception {
        mvc.perform(multipart(TRANSCRIPTIONS).file(file(wav(16_000, 1, 480_000), "audio/wav")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.durationMs").value(30_000));
        mvc.perform(multipart(TRANSCRIPTIONS).file(file(wav(16_000, 1, 480_001), "audio/wav")))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(41300));
    }

    @Test
    void acceptsFiveMebibytesWithLegalJunkAndRejectsOneByteMore() throws Exception {
        byte[] exact = wavWithTotalBytes(5 * 1024 * 1024);
        mvc.perform(multipart(TRANSCRIPTIONS).file(file(exact, "audio/wav")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.durationMs").value(100));
        mvc.perform(multipart(TRANSCRIPTIONS).file(file(Arrays.copyOf(exact, exact.length + 1), "audio/wav")))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(41300));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"text\":null}", "{\"text\":\"  \"}", "{\"text\":{\"value\":\"wrong-shape\"}}",
            "{\"text\":123}", "{\"text\":true}", "{\"text\":[]}",
            "{\"text\":\"sample\",\"voice\":123}", "{\"text\":\"sample\",\"format\":false}"})
    void invalidTextReturnsJsonEvenWhenCallerAcceptsWav(String body) throws Exception {
        mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav").content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void enforcesUnicodeCodePointsRatherThanUtf16Units() throws Exception {
        String emoji = "😀";
        mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + emoji.repeat(2_000) + "\"}"))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/wav"));
        mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav")
                        .content("{\"text\":\"" + emoji.repeat(2_001) + "\"}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(41300))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void unsupportedOutputFormatIs415WithoutLeakingText(CapturedOutput output) throws Exception {
        String marker = "tts-private-marker-d1";
        MvcResult result = mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav")
                        .content("{\"text\":\"" + marker + "\",\"format\":\"mp3\"}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(41500))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @Test
    void springParsingAndMediaErrorsStayJsonAndDoNotLogPayload(CapturedOutput output) throws Exception {
        String marker = "json-private-marker-d1";
        MvcResult result = mvc.perform(post(SPEECH).contentType(MediaType.APPLICATION_JSON).accept("audio/wav")
                        .content("{\"text\":\"" + marker + "\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
        mvc.perform(post(SPEECH).contentType("text/plain").accept("audio/wav").content(marker))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(41500))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(post(TRANSCRIPTIONS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(41500));
    }

    private static MockMultipartFile file(byte[] bytes, String mime) {
        return new MockMultipartFile("file", "sample.wav", mime, bytes);
    }
}
