package com.aiflow.engine.audio;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WAV 输入护栏的纯单元测试：不启动 Spring，也不访问真实语音服务。
 */
class WavPcmValidatorTest {

    @Test
    void rejectsEmptyAudioAsInvalidRequest() {
        assertThatThrownBy(() -> WavPcmValidator.validate(new byte[0]))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.INVALID_REQUEST));
        assertThatThrownBy(() -> WavPcmValidator.validate(null))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.INVALID_REQUEST));
    }

    @Test
    void rejectsRecognizableButTruncatedRiffHeaderAsMalformed() {
        byte[] truncated = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0};

        assertThatThrownBy(() -> WavPcmValidator.validate(truncated))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void rejectsMissingFormatChunkAsMalformed() {
        byte[] source = wav(1, 16_000, 1_600);
        byte[] withoutFormat = new byte[source.length - 24];
        System.arraycopy(source, 0, withoutFormat, 0, 12);
        System.arraycopy(source, 36, withoutFormat, 12, source.length - 36);
        intLE(withoutFormat, 4, withoutFormat.length - 8);

        assertThatThrownBy(() -> WavPcmValidator.validate(withoutFormat))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void rejectsMissingDataChunkAsMalformed() {
        byte[] withoutData = Arrays.copyOf(wav(1, 16_000, 1_600), 36);
        intLE(withoutData, 4, withoutData.length - 8);

        assertThatThrownBy(() -> WavPcmValidator.validate(withoutData))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void nonPcmEncodingUsesUnsupportedParamsEvenWithNonPcmByteRate() {
        byte[] wav = wav(1, 16_000, 1_600);
        shortLE(wav, 20, 7); // Mu-law encoding need not have a PCM byte rate.
        intLE(wav, 28, 16_000);

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_PCM_PARAMS));
    }

    @Test
    void rejectsInvalidSampleRateAsUnsupportedParams() {
        byte[] wav = wav(1, 16_000, 1_600);
        intLE(wav, 24, 0);
        intLE(wav, 28, 0);

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_PCM_PARAMS));
    }

    @Test
    void acceptsExactByteLimitButRejectsOneByteLessAllowance() {
        byte[] wav = wav(1, 16_000, 1_600);
        AudioLimits exact = new AudioLimits(wav.length, Duration.ofSeconds(30), 16_000, 1, 16);
        AudioLimits tooSmall = new AudioLimits(wav.length - 1L, Duration.ofSeconds(30), 16_000, 1, 16);

        assertThat(WavPcmValidator.validate(wav, exact).byteLength()).isEqualTo(wav.length);
        assertThatThrownBy(() -> WavPcmValidator.validate(wav, tooSmall))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.SIZE_LIMIT));
    }

    @Test
    void acceptsMonoSixteenKhzPcm() {
        byte[] wav = wav(1, 16_000, 1_600);

        WavPcmAudio audio = WavPcmValidator.validate(wav);

        assertThat(audio.byteLength()).isEqualTo(wav.length);
        assertThat(audio.dataBytes()).isEqualTo(3_200);
        assertThat(audio.durationMs()).isEqualTo(100);
        assertThat(audio.sampleRate()).isEqualTo(16_000);
        assertThat(audio.channels()).isEqualTo(1);
        assertThat(audio.bitsPerSample()).isEqualTo(16);
    }

    @Test
    void rejectsWrongRiffMagic() {
        byte[] wav = wav(1, 16_000, 1_600);
        wav[0] = 'R';
        wav[1] = 'A';
        wav[2] = 'N';
        wav[3] = 'D';

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_MEDIA));
    }

    @Test
    void rejectsStereoInput() {
        byte[] wav = wav(2, 16_000, 1_600);

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_PCM_PARAMS));
    }

    @Test
    void rejectsNonSixteenKhzInput() {
        byte[] wav = wav(1, 8_000, 800);

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_PCM_PARAMS));
    }

    @Test
    void rejectsNonPcmFormat() {
        byte[] wav = wav(1, 16_000, 1_600);
        shortLE(wav, 20, 3); // IEEE float, not PCM

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_PCM_PARAMS));
    }

    @Test
    void rejectsInconsistentByteRate() {
        byte[] wav = wav(1, 16_000, 1_600);
        intLE(wav, 28, 1);

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void rejectsDuplicateFormatChunk() {
        byte[] wav = appendChunkWithoutPadding(wav(1, 16_000, 1_600), "fmt ", new byte[16]);

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void rejectsDuplicateDataChunk() {
        byte[] wav = appendChunkWithoutPadding(wav(1, 16_000, 1_600), "data", new byte[]{0, 0});

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void rejectsZeroLengthDataChunk() {
        byte[] wav = wav(1, 16_000, 1_600);
        intLE(wav, 40, 0);

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void rejectsTruncatedWav() {
        byte[] wav = wav(1, 16_000, 1_600);
        byte[] truncated = Arrays.copyOf(wav, wav.length - 5);

        assertThatThrownBy(() -> WavPcmValidator.validate(truncated))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void rejectsRiffTrailingGarbage() {
        byte[] valid = wav(1, 16_000, 1_600);
        byte[] withGarbage = Arrays.copyOf(valid, valid.length + 1);
        withGarbage[withGarbage.length - 1] = 0x7f;

        assertThatThrownBy(() -> WavPcmValidator.validate(withGarbage))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void rejectsOddChunkWithoutRequiredPadding() {
        byte[] wav = appendChunkWithoutPadding(wav(1, 16_000, 1_600), "JUNK", new byte[]{0});

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void acceptsOddJunkChunkWithRequiredPadding() {
        byte[] source = wav(1, 16_000, 1_600);
        byte[] wav = appendChunkWithoutPadding(source, "JUNK", new byte[]{0, 0, 0});
        wav = Arrays.copyOf(wav, wav.length + 1);
        intLE(wav, 4, wav.length - 8);

        WavPcmAudio audio = WavPcmValidator.validate(wav);

        assertThat(audio.durationMs()).isEqualTo(100);
        assertThat(audio.dataBytes()).isEqualTo(3_200);
    }

    @Test
    void rejectsDataThatDoesNotContainWholePcmFrames() {
        byte[] wav = wavWithDataLength(3);

        assertThatThrownBy(() -> WavPcmValidator.validate(wav))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.MALFORMED_WAV));
    }

    @Test
    void rejectsInputThatExceedsByteLimitBeforeParsing() {
        byte[] wav = wav(1, 16_000, 1_600);
        AudioLimits limits = new AudioLimits(100, Duration.ofSeconds(30), 16_000, 1, 16);

        assertThatThrownBy(() -> WavPcmValidator.validate(wav, limits))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.SIZE_LIMIT));
    }

    @Test
    void rejectsInputThatExceedsDurationLimit() {
        byte[] wav = wav(1, 16_000, 1_600);
        AudioLimits limits = new AudioLimits(5 * 1024 * 1024L, Duration.ofMillis(50), 16_000, 1, 16);

        assertThatThrownBy(() -> WavPcmValidator.validate(wav, limits))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.DURATION_LIMIT));
    }

    @Test
    void acceptsExactlyThirtySecondsButRejectsOneMoreFrame() {
        byte[] exact = wav(1, 16_000, 16_000 * 30);
        byte[] over = wav(1, 16_000, 16_000 * 30 + 1);

        assertThat(WavPcmValidator.validate(exact).durationMs()).isEqualTo(30_000);
        assertThatThrownBy(() -> WavPcmValidator.validate(over))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.DURATION_LIMIT));
    }

    /** Creates a little-endian PCM WAV with the requested number of sample frames. */
    private static byte[] wav(int channels, int sampleRate, int frames) {
        int bitsPerSample = 16;
        int blockAlign = channels * bitsPerSample / 8;
        int dataLength = frames * blockAlign;
        byte[] bytes = new byte[44 + dataLength];
        ascii(bytes, 0, "RIFF");
        intLE(bytes, 4, 36 + dataLength);
        ascii(bytes, 8, "WAVE");
        ascii(bytes, 12, "fmt ");
        intLE(bytes, 16, 16);
        shortLE(bytes, 20, 1);
        shortLE(bytes, 22, channels);
        intLE(bytes, 24, sampleRate);
        intLE(bytes, 28, sampleRate * blockAlign);
        shortLE(bytes, 32, blockAlign);
        shortLE(bytes, 34, bitsPerSample);
        ascii(bytes, 36, "data");
        intLE(bytes, 40, dataLength);
        return bytes;
    }

    private static void ascii(byte[] bytes, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            bytes[offset + i] = (byte) value.charAt(i);
        }
    }

    private static void shortLE(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void intLE(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static byte[] appendChunkWithoutPadding(byte[] source, String id, byte[] payload) {
        byte[] result = Arrays.copyOf(source, source.length + 8 + payload.length);
        int offset = source.length;
        ascii(result, offset, id);
        intLE(result, offset + 4, payload.length);
        System.arraycopy(payload, 0, result, offset + 8, payload.length);
        intLE(result, 4, result.length - 8);
        return result;
    }

    private static byte[] wavWithDataLength(int dataLength) {
        byte[] source = wav(1, 16_000, 2);
        byte[] result = Arrays.copyOf(source, 44 + dataLength + (dataLength & 1));
        intLE(result, 4, result.length - 8);
        intLE(result, 40, dataLength);
        return result;
    }
}
