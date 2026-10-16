package com.aiflow.engine.api.service;

import com.aiflow.engine.api.dto.AudioSpeechRequest;
import com.aiflow.engine.api.dto.AudioTranscriptionResponse;
import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.AsrResponse;
import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.audio.TtsRequest;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.config.AudioProperties;
import com.aiflow.engine.integration.audio.MockAsrProvider;
import com.aiflow.engine.integration.audio.MockTtsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudioServiceTest {

    private static final String PRIVATE_TEXT = "private audio text marker";

    @Test
    void uploadUsesConfiguredModelAndOnlyWhitelistedLimits() {
        AudioProperties props = configured(true, "mock", true, true, AudioProperties.Limits.defaults(),
                "configured-asr-1", "configured-tts-1");
        AudioTranscriptionResponse result = service(props, new MockAsrProvider(), new MockTtsProvider())
                .transcribe(wavUpload());
        assertThat(result.provider()).isEqualTo("mock");
        assertThat(result.model()).isEqualTo("configured-asr-1");
        assertThat(result.durationMs()).isEqualTo(100);
        assertThat(result.limits().maxBytes()).isEqualTo(5 * 1024 * 1024);
        assertThat(result.limits().maxDurationMs()).isEqualTo(30_000);
        assertThat(result.limits().sampleRate()).isEqualTo(16_000);
        assertThat(result.limits().channels()).isEqualTo(1);
        assertThat(result.limits().bitsPerSample()).isEqualTo(16);
    }

    @Test
    void speechUsesConfiguredModelAndReturnsRevalidatedWav() {
        AudioProperties props = configured(true, "mock", true, true, AudioProperties.Limits.defaults(),
                "configured-asr-1", "configured-tts-1");
        TtsResponse result = service(props, new MockAsrProvider(), new MockTtsProvider())
                .synthesize(new AudioSpeechRequest("hello", null, "audio/wav"));
        assertThat(result.provider()).isEqualTo("mock");
        assertThat(result.model()).isEqualTo("configured-tts-1");
        assertThat(result.contentType()).isEqualTo("audio/wav");
        assertThat(result.durationMs()).isEqualTo(100);
        assertThat(result.audioBytes()).hasSize(3244);
    }

    @Test
    void declaredOversizeRejectsWithoutOpeningUploadStream() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(5L * 1024 * 1024 + 1);
        assertValidation(() -> enabledService().transcribe(file), AudioValidationException.Reason.SIZE_LIMIT);
        verify(file, never()).getInputStream();
    }

    @Test
    void dishonestUploadLengthReadsAtMostLimitPlusOneAndClosesStream() throws IOException {
        AudioProperties.Limits limits = new AudioProperties.Limits(64, Duration.ofSeconds(30), 16_000, 1, 16);
        AsrProvider provider = asrStub();
        TrackingStream stream = new TrackingStream(new byte[128]);
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1L);
        when(file.getContentType()).thenReturn("audio/wav");
        when(file.getInputStream()).thenReturn(stream);

        assertValidation(() -> service(configured(true, "mock", true, true, limits,
                "mock-asr-1", "mock-tts-1"), provider, null).transcribe(file),
                AudioValidationException.Reason.SIZE_LIMIT);
        assertThat(stream.consumed).isEqualTo(65);
        assertThat(stream.closed).isTrue();
        verify(provider, never()).transcribe(any());
    }

    @Test
    void exactDeclaredLimitStillChecksForUndeclaredTrailingByte() throws IOException {
        byte[] wav = wavBytes();
        AudioProperties.Limits limits = new AudioProperties.Limits(wav.length, Duration.ofSeconds(30), 16_000, 1, 16);
        TrackingStream stream = new TrackingStream(wav);
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn((long) wav.length);
        when(file.getContentType()).thenReturn("audio/x-wav");
        when(file.getInputStream()).thenReturn(stream);
        var result = service(configured(true, "mock", true, true, limits, "mock-asr-1", "mock-tts-1"),
                new MockAsrProvider(), null).transcribe(file);
        assertThat(result.durationMs()).isEqualTo(100);
        assertThat(stream.consumed).isEqualTo(wav.length);
        assertThat(stream.closed).isTrue();
    }

    @Test
    void maximumLongByteLimitDoesNotOverflowReadBound() {
        AudioProperties.Limits limits = new AudioProperties.Limits(Long.MAX_VALUE, Duration.ofSeconds(30), 16_000, 1, 16);
        assertThat(service(configured(true, "mock", true, true, limits, "mock-asr-1", "mock-tts-1"),
                new MockAsrProvider(), null).transcribe(wavUpload()).durationMs()).isEqualTo(100);
    }

    @Test
    void unsupportedOrMissingPartMimeRejectsBeforeStreamRead() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(100L);
        when(file.getContentType()).thenReturn("audio/mpeg");
        assertValidation(() -> enabledService().transcribe(file), AudioValidationException.Reason.UNSUPPORTED_MEDIA);
        when(file.getContentType()).thenReturn(null);
        assertValidation(() -> enabledService().transcribe(file), AudioValidationException.Reason.UNSUPPORTED_MEDIA);
        verify(file, never()).getInputStream();
    }

    @Test
    void uploadIoFailureDiscardsExternalMessageAndCause() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(100L);
        when(file.getContentType()).thenReturn("audio/wav");
        when(file.getInputStream()).thenThrow(new IOException(PRIVATE_TEXT));
        assertThatThrownBy(() -> enabledService().transcribe(file))
                .isInstanceOf(AudioValidationException.class).hasNoCause().hasMessageNotContaining(PRIVATE_TEXT);
    }

    @Test
    void uploadRuntimeFailureDiscardsExternalMessageAndCause() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(100L);
        when(file.getContentType()).thenReturn("audio/wav");
        when(file.getInputStream()).thenThrow(new IllegalStateException(PRIVATE_TEXT));
        assertValidation(() -> enabledService().transcribe(file), AudioValidationException.Reason.INVALID_REQUEST);
    }

    @Test
    void limitFailureAlsoDiscardsSuppressedStreamCloseFailure() throws IOException {
        AudioProperties.Limits limits = new AudioProperties.Limits(64, Duration.ofSeconds(30), 16_000, 1, 16);
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1L);
        when(file.getContentType()).thenReturn("audio/wav");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[128]) {
            @Override
            public void close() throws IOException {
                throw new IOException(PRIVATE_TEXT);
            }
        });
        assertThatThrownBy(() -> service(configured(true, "mock", true, true, limits, "mock-asr-1", "mock-tts-1"),
                asrStub(), null).transcribe(file))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.SIZE_LIMIT))
                .hasNoCause().satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
    }

    @Test
    void globalAndPerChannelSwitchesRejectBeforeUsingProviders() {
        AsrProvider asr = asrStub();
        TtsProvider tts = ttsStub();
        AudioService globalOff = service(AudioProperties.defaults(), asr, tts);
        assertUnavailable(() -> globalOff.transcribe(wavUpload()));
        assertUnavailable(() -> globalOff.synthesize(speechRequest()));
        AudioService channelsOff = service(configured(true, "mock", false, false, AudioProperties.Limits.defaults(),
                "mock-asr-1", "mock-tts-1"), asr, tts);
        assertUnavailable(() -> channelsOff.transcribe(wavUpload()));
        assertUnavailable(() -> channelsOff.synthesize(speechRequest()));
        verify(asr, never()).available();
        verify(tts, never()).available();
        verify(asr, never()).transcribe(any());
        verify(tts, never()).synthesize(any());
    }

    @Test
    void absentProviderAndNonMockModeAreUnavailable() {
        assertUnavailable(() -> service(enabledProperties(), null, null).transcribe(wavUpload()));
        assertUnavailable(() -> service(enabledProperties(), null, null).synthesize(speechRequest()));
        AsrProvider asr = asrStub();
        TtsProvider tts = ttsStub();
        AudioService cloud = service(configured(true, "cloud", true, true, AudioProperties.Limits.defaults(),
                "mock-asr-1", "mock-tts-1"), asr, tts);
        assertUnavailable(() -> cloud.transcribe(wavUpload()));
        assertUnavailable(() -> cloud.synthesize(speechRequest()));
        verify(asr, never()).available();
        verify(tts, never()).available();
    }

    @Test
    void nonMockAsrConfigurationRejectsAnAvailableMockBean() {
        AudioProperties defaults = enabledProperties();
        AudioProperties properties = new AudioProperties(true, "mock",
                new AudioProperties.Asr(true, "cloud", "mock-asr-1", "AIFLOW_AUDIO_ASR_API_KEY"),
                defaults.tts(), defaults.limits());
        AsrProvider asr = asrStub();
        assertUnavailable(() -> service(properties, asr, null).transcribe(wavUpload()));
        verify(asr, never()).id();
        verify(asr, never()).available();
        verify(asr, never()).transcribe(any());
    }

    @Test
    void nonMockTtsConfigurationRejectsAnAvailableMockBean() {
        AudioProperties defaults = enabledProperties();
        AudioProperties properties = new AudioProperties(true, "mock", defaults.asr(),
                new AudioProperties.Tts(true, "cloud", "mock-tts-1", "AIFLOW_AUDIO_TTS_API_KEY"),
                defaults.limits());
        TtsProvider tts = ttsStub();
        assertUnavailable(() -> service(properties, null, tts).synthesize(speechRequest()));
        verify(tts, never()).id();
        verify(tts, never()).available();
        verify(tts, never()).synthesize(any());
    }

    @Test
    void nonMockActualAsrBeanRejectsWithoutCallingItsAvailabilityOrTranscription() {
        AsrProvider asr = asrStub();
        when(asr.id()).thenReturn("cloud");
        assertUnavailable(() -> service(enabledProperties(), asr, null).transcribe(wavUpload()));
        verify(asr, never()).available();
        verify(asr, never()).transcribe(any());
    }

    @Test
    void nonMockActualTtsBeanRejectsWithoutCallingItsAvailabilityOrSynthesis() {
        TtsProvider tts = ttsStub();
        when(tts.id()).thenReturn("cloud");
        assertUnavailable(() -> service(enabledProperties(), null, tts).synthesize(speechRequest()));
        verify(tts, never()).available();
        verify(tts, never()).synthesize(any());
    }

    @Test
    void unavailableOrFailingAvailabilityIsSanitized() {
        AsrProvider asr = asrStub();
        TtsProvider tts = ttsStub();
        when(asr.available()).thenReturn(false);
        when(tts.available()).thenThrow(new IllegalStateException(PRIVATE_TEXT));
        assertUnavailable(() -> service(enabledProperties(), asr, tts).transcribe(wavUpload()));
        assertUnavailable(() -> service(enabledProperties(), asr, tts).synthesize(speechRequest()));
    }

    @Test
    void malformedSpeechRequestsNeverCallProvider() {
        TtsProvider tts = ttsStub();
        AudioService service = service(enabledProperties(), null, tts);
        assertValidation(() -> service.synthesize(null), AudioValidationException.Reason.INVALID_REQUEST);
        assertValidation(() -> service.synthesize(new AudioSpeechRequest(" ", null, null)),
                AudioValidationException.Reason.INVALID_REQUEST);
        assertValidation(() -> service.synthesize(new AudioSpeechRequest("a".repeat(2001), null, null)),
                AudioValidationException.Reason.TEXT_LIMIT);
        assertValidation(() -> service.synthesize(new AudioSpeechRequest("a", null, "mp3")),
                AudioValidationException.Reason.UNSUPPORTED_MEDIA);
        verify(tts, never()).synthesize(any());
    }

    @Test
    void arbitraryProviderFailureDoesNotRetainExternalException() {
        TtsProvider tts = ttsStub();
        when(tts.synthesize(any())).thenThrow(new IllegalStateException(PRIVATE_TEXT));
        assertProvider(() -> service(enabledProperties(), null, tts).synthesize(speechRequest()),
                AudioProviderException.Reason.CALL_FAILED);
    }

    @Test
    void providerValidationAfterValidInputIsAnInvalidOutput() {
        TtsProvider tts = ttsStub();
        when(tts.synthesize(any())).thenThrow(new AudioValidationException(AudioValidationException.Reason.DURATION_LIMIT));
        assertProvider(() -> service(enabledProperties(), null, tts).synthesize(speechRequest()),
                AudioProviderException.Reason.INVALID_RESPONSE);
    }

    @Test
    void providerOutputsCannotOverrideConfiguredMetadataOrInjectHeaders() {
        TtsProvider tts = ttsStub();
        when(tts.synthesize(any())).thenReturn(new TtsResponse(wavBytes(), "audio/wav", "mock",
                "model\r\n" + PRIVATE_TEXT, 100));
        assertProvider(() -> service(enabledProperties(), null, tts).synthesize(speechRequest()),
                AudioProviderException.Reason.INVALID_RESPONSE);
        when(tts.synthesize(any())).thenReturn(new TtsResponse(wavBytes(), "audio/wav", "mock", "other-model", 100));
        assertProvider(() -> service(enabledProperties(), null, tts).synthesize(speechRequest()),
                AudioProviderException.Reason.INVALID_RESPONSE);
    }

    @Test
    void invalidConfiguredMetadataRejectsBeforeSynthesis() {
        TtsProvider tts = ttsStub();
        var props = configured(true, "mock", true, true, AudioProperties.Limits.defaults(), "mock-asr-1", "bad\r\nmodel");
        assertProvider(() -> service(props, null, tts).synthesize(speechRequest()),
                AudioProviderException.Reason.INVALID_RESPONSE);
        verify(tts, never()).synthesize(any());
    }

    @Test
    void transcribedDurationMustMatchValidatedInput() {
        AsrProvider asr = asrStub();
        when(asr.transcribe(any())).thenReturn(new AsrResponse(PRIVATE_TEXT, "mock", "mock-asr-1", 99));
        assertProvider(() -> service(enabledProperties(), asr, null).transcribe(wavUpload()),
                AudioProviderException.Reason.INVALID_RESPONSE);
    }

    @Test
    void speechAndTranscriptionDtosDoNotExposeBodyOrArbitraryMetadataInToString() {
        assertThat(new AudioSpeechRequest(PRIVATE_TEXT, PRIVATE_TEXT, PRIVATE_TEXT).toString())
                .doesNotContain(PRIVATE_TEXT);
        assertThat(new AudioTranscriptionResponse(PRIVATE_TEXT, PRIVATE_TEXT, PRIVATE_TEXT, 100, null).toString())
                .doesNotContain(PRIVATE_TEXT);
    }

    private static AudioSpeechRequest speechRequest() {
        return new AudioSpeechRequest("hello", null, null);
    }

    private static byte[] wavBytes() {
        return new MockTtsProvider().synthesize(new TtsRequest("hello", null, null, null)).audioBytes();
    }

    private static MockMultipartFile wavUpload() {
        return new MockMultipartFile("file", "sample.wav", "audio/wav", wavBytes());
    }

    private static AudioService enabledService() {
        return service(enabledProperties(), new MockAsrProvider(), new MockTtsProvider());
    }

    private static AudioProperties enabledProperties() {
        return configured(true, "mock", true, true, AudioProperties.Limits.defaults(), "mock-asr-1", "mock-tts-1");
    }

    private static AudioProperties configured(boolean enabled, String mode, boolean asrEnabled, boolean ttsEnabled,
                                               AudioProperties.Limits limits, String asrModel, String ttsModel) {
        return new AudioProperties(enabled, mode,
                new AudioProperties.Asr(asrEnabled, "mock", asrModel, "AIFLOW_AUDIO_ASR_API_KEY"),
                new AudioProperties.Tts(ttsEnabled, "mock", ttsModel, "AIFLOW_AUDIO_TTS_API_KEY"), limits);
    }

    @SuppressWarnings("unchecked")
    private static AudioService service(AudioProperties props, AsrProvider asr, TtsProvider tts) {
        ObjectProvider<AsrProvider> asrProviders = mock(ObjectProvider.class);
        ObjectProvider<TtsProvider> ttsProviders = mock(ObjectProvider.class);
        when(asrProviders.getIfAvailable()).thenReturn(asr);
        when(ttsProviders.getIfAvailable()).thenReturn(tts);
        return new AudioService(props, asrProviders, ttsProviders);
    }

    private static AsrProvider asrStub() {
        AsrProvider provider = mock(AsrProvider.class);
        when(provider.id()).thenReturn("mock");
        when(provider.available()).thenReturn(true);
        return provider;
    }

    private static TtsProvider ttsStub() {
        TtsProvider provider = mock(TtsProvider.class);
        when(provider.id()).thenReturn("mock");
        when(provider.available()).thenReturn(true);
        return provider;
    }

    private static void assertValidation(Runnable operation, AudioValidationException.Reason reason) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(AudioValidationException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasNoCause().hasMessageNotContaining(PRIVATE_TEXT);
    }

    private static void assertUnavailable(Runnable operation) {
        assertProvider(operation, AudioProviderException.Reason.UNAVAILABLE);
    }

    private static void assertProvider(Runnable operation, AudioProviderException.Reason reason) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(AudioProviderException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasNoCause().hasMessageNotContaining(PRIVATE_TEXT);
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        private int consumed;
        private boolean closed;

        private TrackingStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                consumed += count;
            }
            return count;
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value >= 0) {
                consumed++;
            }
            return value;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
