package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AsrRequest;
import com.aiflow.engine.audio.AsrResponse;
import com.aiflow.engine.audio.AudioInput;
import com.aiflow.engine.audio.AudioLimits;
import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.TtsRequest;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.audio.WavPcmAudio;
import com.aiflow.engine.audio.WavPcmValidator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Offline contract checks only; neither provider makes a real network request. */
class MockAudioProviderTest {

    @Test
    void nullProviderRequestsUseInvalidRequestReason() {
        assertThatThrownBy(() -> new MockAsrProvider().transcribe(null))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.INVALID_REQUEST));
        assertThatThrownBy(() -> new MockTtsProvider().synthesize(null))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.INVALID_REQUEST));
    }

    @Test
    void ttsAvailabilityRequiresSpaceForTheMinimumFixedWav() {
        assertThat(new MockTtsProvider(new AudioLimits(3_244, Duration.ofMillis(100), 16_000, 1, 16)).available()).isTrue();
        assertThat(new MockTtsProvider(new AudioLimits(3_243, Duration.ofMillis(100), 16_000, 1, 16)).available()).isFalse();
        assertThat(new MockTtsProvider(new AudioLimits(3_244, Duration.ofMillis(99), 16_000, 1, 16)).available()).isFalse();
    }

    @Test
    void ttsAvailabilityRejectsFormatsTheFixedMockOutputCannotProduce() {
        MockTtsProvider provider = new MockTtsProvider(new AudioLimits(5 * 1024 * 1024L, Duration.ofSeconds(30), 8_000, 1, 16));

        assertThat(provider.available()).isFalse();
        assertThatThrownBy(() -> provider.synthesize(new TtsRequest("测试", null, null, null)))
                .isInstanceOfSatisfying(AudioProviderException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioProviderException.Reason.UNAVAILABLE));
        assertThat(new MockTtsProvider(new AudioLimits(3_244, Duration.ofMillis(100), 16_000, 2, 16)).available()).isFalse();
        assertThat(new MockTtsProvider(new AudioLimits(3_244, Duration.ofMillis(100), 16_000, 1, 24)).available()).isFalse();
    }

    @Test
    void asrAvailabilityRequiresSpaceForAtLeastOnePcmFrame() {
        assertThat(new MockAsrProvider(new AudioLimits(46, Duration.ofMillis(1), 16_000, 1, 16)).available()).isTrue();
        assertThat(new MockAsrProvider(new AudioLimits(45, Duration.ofMillis(1), 16_000, 1, 16)).available()).isFalse();
        assertThat(new MockAsrProvider(new AudioLimits(46, Duration.ofMillis(50), 8_000, 1, 16)).available()).isTrue();
        assertThat(new MockAsrProvider(new AudioLimits(46, Duration.ofMillis(1), 1, 1, 16)).available()).isFalse();
    }

    @Test
    void ttsReturnsValidMonoSixteenKhzWavWithExplicitMockIdentity() {
        MockTtsProvider provider = new MockTtsProvider();

        TtsResponse response = provider.synthesize(new TtsRequest("测试音频", null, null, null));
        WavPcmAudio audio = WavPcmValidator.validate(response.audioBytes());

        assertThat(provider.available()).isTrue();
        assertThat(provider.description()).contains("契约桩");
        assertThat(response.contentType()).isEqualTo("audio/wav");
        assertThat(response.provider()).isEqualTo("mock");
        assertThat(response.model()).isEqualTo("mock-tts-1");
        assertThat(response.durationMs()).isEqualTo(audio.durationMs());
        assertThat(audio.sampleRate()).isEqualTo(16_000);
        assertThat(audio.channels()).isEqualTo(1);
        assertThat(audio.bitsPerSample()).isEqualTo(16);
    }

    @Test
    void asrValidatesAudioAndReturnsMockTextWithoutAudioBody() {
        byte[] wav = new MockTtsProvider().synthesize(new TtsRequest("测试音频", null, null, null)).audioBytes();
        MockAsrProvider provider = new MockAsrProvider();

        AsrResponse response = provider.transcribe(new AsrRequest(new AudioInput(wav, "audio/wav"), null));

        assertThat(provider.available()).isTrue();
        assertThat(provider.description()).contains("契约桩");
        assertThat(response.provider()).isEqualTo("mock");
        assertThat(response.model()).isEqualTo("mock-asr-1");
        assertThat(response.durationMs()).isEqualTo(WavPcmValidator.validate(wav).durationMs());
        assertThat(response.text()).startsWith("（mock ASR）");
    }

    @Test
    void asrRejectsMalformedAudioInsteadOfPretendingToTranscribeIt() {
        MockAsrProvider provider = new MockAsrProvider();

        assertThatThrownBy(() -> provider.transcribe(new AsrRequest(new AudioInput(new byte[]{1, 2, 3}, "audio/wav"), null)))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_MEDIA));
    }

    @Test
    void mockTtsRejectsUnsupportedOutputFormat() {
        MockTtsProvider provider = new MockTtsProvider();

        assertThatThrownBy(() -> provider.synthesize(new TtsRequest("测试", null, "mp3", null)))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_MEDIA));
    }

    @Test
    void ttsRejectsBlankAndOverlongTextBeforeSynthesis() {
        assertThatThrownBy(() -> new TtsRequest("   ", null, null, null))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.INVALID_REQUEST));
        assertThatThrownBy(() -> new TtsRequest("字".repeat(2_001), null, null, null))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.TEXT_LIMIT));
    }

    @Test
    void ttsHonorsConfiguredByteLimit() {
        AudioLimits limits = new AudioLimits(100, Duration.ofSeconds(30), 16_000, 1, 16);
        MockTtsProvider provider = new MockTtsProvider(limits);

        assertThatThrownBy(() -> provider.synthesize(new TtsRequest("测试", null, null, null)))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.SIZE_LIMIT));
    }

    @Test
    void ttsHonorsConfiguredDurationLimit() {
        AudioLimits limits = new AudioLimits(5 * 1024 * 1024L, Duration.ofMillis(50), 16_000, 1, 16);
        MockTtsProvider provider = new MockTtsProvider(limits);

        assertThatThrownBy(() -> provider.synthesize(new TtsRequest("测试", null, null, null)))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.DURATION_LIMIT));
    }

    @Test
    void providerDefaultsAndExplicitModelOverridesAreReported() {
        MockTtsProvider tts = new MockTtsProvider(AudioLimits.defaults(), "configured-tts");
        TtsResponse defaults = tts.synthesize(new TtsRequest("测试", null, null, null));
        TtsResponse explicit = tts.synthesize(new TtsRequest("测试", null, null, "request-tts"));
        MockAsrProvider asr = new MockAsrProvider(AudioLimits.defaults(), "configured-asr");
        AudioInput input = new AudioInput(defaults.audioBytes(), null);

        assertThat(defaults.model()).isEqualTo("configured-tts");
        assertThat(explicit.model()).isEqualTo("request-tts");
        assertThat(asr.transcribe(new AsrRequest(input, null)).model()).isEqualTo("configured-asr");
        assertThat(asr.transcribe(new AsrRequest(input, "request-asr")).model()).isEqualTo("request-asr");
    }
}
