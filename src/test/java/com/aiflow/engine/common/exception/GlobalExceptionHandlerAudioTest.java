package com.aiflow.engine.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.provider.CapabilityUnavailableException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Audio error responses must retain their status, JSON media type and payload redaction. */
class GlobalExceptionHandlerAudioTest {
    private static final String SENSITIVE = "synthetic-audio-sensitive-marker";
    private static final String SPEECH = "/api/v1/audio/speech";
    private static final String TRANSCRIPTIONS = "/api/v1/audio/transcriptions";
    private static final String VALID_JSON = "{\"text\":\"valid\"}";

    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void stopLogging() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
    }

    @ParameterizedTest
    @EnumSource(AudioValidationException.Reason.class)
    void everyValidationReasonKeepsItsClientStatusWithWavAccept(AudioValidationException.Reason reason)
            throws Exception {
        int expectedStatus = switch (reason) {
            case INVALID_REQUEST, MALFORMED_WAV -> 400;
            case UNSUPPORTED_MEDIA, UNSUPPORTED_PCM_PARAMS -> 415;
            case SIZE_LIMIT, DURATION_LIMIT, TEXT_LIMIT -> 413;
        };
        assertError(speechFailure("validation:" + reason.name()), expectedStatus);
        assertRedactedLogs();
    }

    @ParameterizedTest
    @EnumSource(AudioProviderException.Reason.class)
    void mockUpstreamFailureKeepsItsStatusWithWavAccept(AudioProviderException.Reason reason) throws Exception {
        int expectedStatus = switch (reason) {
            case UNAVAILABLE -> 503;
            case TIMEOUT -> 504;
            case REJECTED, INVALID_RESPONSE, CALL_FAILED -> 502;
        };
        assertError(speechFailure("provider:" + reason.name()), expectedStatus);
        assertRedactedLogs();
    }

    @ParameterizedTest
    @ValueSource(strings = {SPEECH, TRANSCRIPTIONS})
    void servletUploadLimitIs413ForEachExactAudioEndpoint(String path) throws Exception {
        var request = post(path).param("failure", "upload").accept("audio/wav");
        if (SPEECH.equals(path)) {
            request.contentType(MediaType.APPLICATION_JSON).content(VALID_JSON);
        }
        assertError(mvc.perform(request), 413);
        assertRedactedLogs();
    }

    @Test
    void audioUploadLimitRecognizesServletContextPath() throws Exception {
        assertError(mvc.perform(post("/engine" + TRANSCRIPTIONS).contextPath("/engine")
                .param("failure", "upload").accept("audio/wav")), 413);
        assertRedactedLogs();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/attachments", "/api/v1/audio/speech/extra", "/api/v1/audioevil",
            "/prefix/api/v1/audio/transcriptions"})
    void existingUploadsAndAudioLookalikesKeepTheir400Contract(String path) throws Exception {
        mvc.perform(post(path).param("failure", "upload").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("上传文件超过大小上限，请压缩后重试"));
        assertRedactedLogs();
    }

    @Test
    void legacyDirectUploadHandlerSignatureStillKeeps400() {
        var response = new GlobalExceptionHandler().handleUploadTooLarge(uploadFailure());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(40000);
        assertRedactedLogs();
    }

    @Test
    void malformedJsonIs400JsonDespiteWavAcceptWithoutEchoingTheBody() throws Exception {
        assertError(mvc.perform(post(SPEECH).param("failure", "runtime").accept("audio/wav")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + SENSITIVE + "\"")), 400);
        assertRedactedLogs();
    }

    @Test
    void missingParameterIs400JsonDespiteWavAccept() throws Exception {
        assertError(mvc.perform(post(SPEECH).accept("audio/wav")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_JSON)), 400);
        assertRedactedLogs();
    }

    @Test
    void beanValidationDoesNotEchoValidationMessages() throws Exception {
        assertError(mvc.perform(post(SPEECH).param("failure", "runtime").accept("audio/wav")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"\"}")), 400);
        assertRedactedLogs();
    }

    @Test
    void wrongContentTypeIs415JsonDespiteWavAccept() throws Exception {
        assertError(mvc.perform(post(SPEECH).param("failure", "runtime").accept("audio/wav")
                .contentType(MediaType.TEXT_PLAIN).content(SENSITIVE)), 415);
        assertRedactedLogs();
    }

    @Test
    void wrongMethodIs405JsonDespiteWavAccept() throws Exception {
        assertError(mvc.perform(get(SPEECH).accept("audio/wav")), 405);
        assertRedactedLogs();
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "io", "preset-runtime"})
    void unexpectedAudioFailureIs500JsonWithoutCauseOrPayload(String failure) throws Exception {
        assertError(speechFailure(failure), 500);
        assertRedactedLogs();
    }

    @Test
    void audioBizExceptionDoesNotEchoMessageOrData() throws Exception {
        assertError(speechFailure("biz"), 400);
        assertRedactedLogs();
    }

    @Test
    void audioCapabilityExceptionDoesNotEchoProviderDetail() throws Exception {
        assertError(speechFailure("capability"), 503);
        assertRedactedLogs();
    }

    @Test
    void disconnectedAudioResponseDoesNotLogTheRawTransportMessage() throws Exception {
        speechFailure("disconnect").andExpect(content().string(""));
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("audio client disconnected, response no longer usable"));
        assertRedactedLogs();
    }

    private ResultActions speechFailure(String failure) throws Exception {
        return mvc.perform(post(SPEECH).param("failure", failure).accept("audio/wav")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_JSON));
    }

    private void assertError(ResultActions result, int expectedStatus) throws Exception {
        ErrorCode expectedCode = ErrorCode.fromHttpStatus(expectedStatus);
        result.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(expectedCode.getCode()))
                .andExpect(jsonPath("$.message").value(expectedCode.getDefaultMessage()))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(response -> assertThat(response.getResponse().getContentAsString())
                        .doesNotContain(SENSITIVE));
    }

    private void assertRedactedLogs() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SENSITIVE);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static MaxUploadSizeExceededException uploadFailure() {
        return new MaxUploadSizeExceededException(123, new IllegalStateException(SENSITIVE)) {
            @Override
            public String getMessage() {
                return SENSITIVE;
            }
        };
    }

    @RestController
    static class FailureController {
        @PostMapping(value = SPEECH, consumes = MediaType.APPLICATION_JSON_VALUE)
        void speech(@Valid @RequestBody SpeechInput ignored, @RequestParam("failure") String failure,
                    HttpServletResponse response) throws IOException {
            fail(failure, response);
        }

        @PostMapping({TRANSCRIPTIONS, "/api/v1/attachments", "/api/v1/audio/speech/extra",
                "/api/v1/audioevil", "/prefix/api/v1/audio/transcriptions"})
        void upload(@RequestParam("failure") String failure, HttpServletResponse response) throws IOException {
            fail(failure, response);
        }

        private void fail(String failure, HttpServletResponse response) throws IOException {
            if (failure.startsWith("validation:")) {
                throw new AudioValidationException(AudioValidationException.Reason.valueOf(failure.substring(11))) {
                    @Override
                    public String getMessage() {
                        return SENSITIVE;
                    }
                };
            }
            if (failure.startsWith("provider:")) {
                throw new AudioProviderException(AudioProviderException.Reason.valueOf(failure.substring(9)),
                        new IllegalStateException(SENSITIVE)) {
                    @Override
                    public String getMessage() {
                        return SENSITIVE;
                    }
                };
            }
            switch (failure) {
                case "upload" -> throw uploadFailure();
                case "biz" -> throw new BizException(ErrorCode.BAD_REQUEST, SENSITIVE, Map.of("text", SENSITIVE));
                case "capability" -> throw new CapabilityUnavailableException(Set.of(SENSITIVE));
                case "io" -> throw new IOException(SENSITIVE, new IllegalStateException(SENSITIVE));
                case "disconnect" -> throw new AsyncRequestNotUsableException(SENSITIVE);
                case "preset-runtime" -> {
                    response.setContentType("audio/wav");
                    throw new IllegalStateException(SENSITIVE, new IOException(SENSITIVE));
                }
                default -> throw new IllegalStateException(SENSITIVE, new IOException(SENSITIVE));
            }
        }
    }

    record SpeechInput(@NotBlank(message = SENSITIVE) String text) {
    }
}
