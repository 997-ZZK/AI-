package com.aiflow.engine.audio;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioDtoTest {

    @Test
    void validationFailureUsesFixedReasonAndNeverEchoesCallerContent() {
        String privateContent = "private-audio-request-content";

        assertThatThrownBy(() -> new AudioInput(new byte[]{1, 2, 3}, "audio/" + privateContent))
                .isInstanceOfSatisfying(AudioValidationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_MEDIA);
                    assertThat(failure.getMessage()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_MEDIA.message());
                    assertThat(failure.toString()).doesNotContain(privateContent);
                    assertThat(failure.getCause()).isNull();
                });
        assertThatThrownBy(() -> new TtsRequest(privateContent.repeat(200), null, null, null))
                .isInstanceOfSatisfying(AudioValidationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.TEXT_LIMIT);
                    assertThat(failure.getMessage()).isEqualTo(AudioValidationException.Reason.TEXT_LIMIT.message());
                    assertThat(failure.toString()).doesNotContain(privateContent);
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void unspecifiedValidationReasonDefaultsToInvalidRequest() {
        AudioValidationException failure = new AudioValidationException(null);

        assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.INVALID_REQUEST);
        assertThat(failure.getMessage()).isEqualTo(AudioValidationException.Reason.INVALID_REQUEST.message());
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void missingAudioInputsUseInvalidRequestReason() {
        assertThatThrownBy(() -> new AudioInput(null, null))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.INVALID_REQUEST));
        assertThatThrownBy(() -> new AudioInput(new byte[0], null))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.INVALID_REQUEST));
        assertThatThrownBy(() -> new AsrRequest(null, null))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.INVALID_REQUEST));
    }

    @Test
    void ttsTextLimitCountsUnicodeCodePointsInsteadOfUtf16Units() {
        String emoji = new String(Character.toChars(0x1f600));
        TtsRequest exact = new TtsRequest(emoji.repeat(2_000), null, null, null);

        assertThat(exact.text().length()).isEqualTo(4_000);
        assertThat(exact.text().codePointCount(0, exact.text().length())).isEqualTo(2_000);
        assertThatThrownBy(() -> new TtsRequest(emoji.repeat(2_001), null, null, null))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.TEXT_LIMIT));
    }

    @Test
    void providerExceptionDoesNotExposeExternalMessageOrCause() {
        RuntimeException cause = new RuntimeException("vendor response with secret-key");
        AudioProviderException exception = new AudioProviderException(AudioProviderException.Reason.CALL_FAILED, cause);

        assertThat(exception.getMessage()).doesNotContain("secret-key");
        assertThat(exception.getCause()).isNull();
        assertThat(exception.reason()).isEqualTo(AudioProviderException.Reason.CALL_FAILED);
    }

    @Test
    void audioInputRejectsUnsupportedMimeType() {
        assertThatThrownBy(() -> new AudioInput(new byte[]{1, 2, 3}, "audio/mpeg"))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_MEDIA));
    }

    @Test
    void audioInputAcceptsWaveMimeAliases() {
        assertThat(new AudioInput(new byte[]{1}, "audio/wav").mimeType()).isEqualTo("audio/wav");
        assertThat(new AudioInput(new byte[]{1}, "audio/x-wav").mimeType()).isEqualTo("audio/x-wav");
        assertThat(new AudioInput(new byte[]{1}, "audio/wave").mimeType()).isEqualTo("audio/wave");
    }

    @Test
    void audioMimeAndTtsFormatNormalizationIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(new AudioInput(new byte[]{1}, "AUDIO/WAV").mimeType()).isEqualTo("audio/wav");
            assertThat(new TtsRequest("测试", null, "AUDIO/WAV", null).format()).isEqualTo("audio/wav");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void dtoStringRepresentationsDoNotEchoCallerData() {
        String privateText = "private sample transcript";
        String privateMetadata = "private model metadata";
        AudioInput audio = new AudioInput(new byte[]{1, 2, 3}, null);

        assertThat(new AsrRequest(audio, privateMetadata).toString()).doesNotContain(privateMetadata);
        assertThat(new AsrResponse(privateText, privateMetadata, privateMetadata, 100).toString())
                .doesNotContain(privateText).doesNotContain(privateMetadata);
        assertThat(new TtsRequest(privateText, privateMetadata, privateMetadata, privateMetadata).toString())
                .doesNotContain(privateText).doesNotContain(privateMetadata);
        assertThat(new TtsResponse(new byte[]{1}, privateMetadata, privateMetadata, privateMetadata, 100).toString())
                .doesNotContain(privateMetadata);
        assertThat(audio.toString()).contains("byteLength=3").doesNotContain("1, 2, 3");
    }

    @Test
    void audioInputUsesDefensiveCopies() {
        byte[] source = new byte[]{1, 2, 3};
        AudioInput input = new AudioInput(source, null);

        source[0] = 9;
        byte[] returned = input.bytes();
        returned[1] = 9;

        assertThat(input.bytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(input.mimeType()).isEqualTo("audio/wav");
    }

    @Test
    void ttsResponseUsesDefensiveCopies() {
        byte[] source = new byte[]{1, 2, 3};
        TtsResponse response = new TtsResponse(source, "audio/wav", "mock", "mock-tts-1", 100);

        source[0] = 9;
        byte[] returned = response.audioBytes();
        returned[1] = 9;

        assertThat(response.audioBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
    }
}
