package com.aiflow.engine.audio;

import java.nio.charset.StandardCharsets;

/** 严格解析 RIFF/WAVE PCM 文件头，并同时执行大小、采样率、声道和时长限制。 */
public final class WavPcmValidator {

    private WavPcmValidator() {
    }

    public static WavPcmAudio validate(byte[] bytes) {
        return validate(bytes, AudioLimits.defaults());
    }

    public static WavPcmAudio validate(byte[] bytes, AudioLimits limits) {
        if (bytes == null || bytes.length == 0) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        if (limits == null) {
            throw new IllegalArgumentException("音频限制不能为空");
        }
        if (bytes.length > limits.maxBytes()) {
            throw new AudioValidationException(AudioValidationException.Reason.SIZE_LIMIT);
        }
        if (!ascii(bytes, 0, "RIFF")) {
            throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_MEDIA);
        }
        if (bytes.length < 12) {
            throw new AudioValidationException(AudioValidationException.Reason.MALFORMED_WAV);
        }
        if (!ascii(bytes, 8, "WAVE")) {
            throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_MEDIA);
        }

        long riffSize = unsignedInt(bytes, 4);
        long riffEnd = 8L + riffSize;
        if (riffSize < 4 || riffEnd != bytes.length) {
            throw new AudioValidationException(AudioValidationException.Reason.MALFORMED_WAV);
        }

        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        long dataBytes = -1;
        boolean formatSeen = false;
        int offset = 12;
        while (offset + 8 <= riffEnd && offset + 8 <= bytes.length) {
            String chunk = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            long chunkSize = unsignedInt(bytes, offset + 4);
            long payloadEnd = offset + 8L + chunkSize;
            if (payloadEnd > riffEnd || payloadEnd > bytes.length) {
                throw new AudioValidationException(AudioValidationException.Reason.MALFORMED_WAV);
            }
            if ("fmt ".equals(chunk)) {
                if (formatSeen || chunkSize < 16) {
                    throw new AudioValidationException(AudioValidationException.Reason.MALFORMED_WAV);
                }
                formatSeen = true;
                int p = offset + 8;
                int audioFormat = unsignedShort(bytes, p);
                if (audioFormat != 1) {
                    throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_PCM_PARAMS);
                }
                channels = unsignedShort(bytes, p + 2);
                long rate = unsignedInt(bytes, p + 4);
                if (rate > Integer.MAX_VALUE) {
                    throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_PCM_PARAMS);
                }
                sampleRate = (int) rate;
                bitsPerSample = unsignedShort(bytes, p + 14);
                long blockAlign = unsignedShort(bytes, p + 12);
                long byteRate = unsignedInt(bytes, p + 8);
                long expectedAlign = (long) channels * bitsPerSample / 8;
                if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || bitsPerSample % 8 != 0) {
                    throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_PCM_PARAMS);
                }
                if (blockAlign != expectedAlign || byteRate != (long) sampleRate * expectedAlign) {
                    throw new AudioValidationException(AudioValidationException.Reason.MALFORMED_WAV);
                }
            } else if ("data".equals(chunk)) {
                if (dataBytes >= 0 || chunkSize == 0) {
                    throw new AudioValidationException(AudioValidationException.Reason.MALFORMED_WAV);
                }
                dataBytes = chunkSize;
            }
            long next = payloadEnd + (chunkSize & 1L);
            if (next > Integer.MAX_VALUE || next > riffEnd) {
                throw new AudioValidationException(AudioValidationException.Reason.MALFORMED_WAV);
            }
            offset = (int) next;
        }

        if (offset != riffEnd) {
            throw new AudioValidationException(AudioValidationException.Reason.MALFORMED_WAV);
        }

        if (!formatSeen || dataBytes < 0) {
            throw new AudioValidationException(AudioValidationException.Reason.MALFORMED_WAV);
        }
        if (sampleRate != limits.sampleRate() || channels != limits.channels()
                || bitsPerSample != limits.bitsPerSample()) {
            throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_PCM_PARAMS);
        }
        long bytesPerSecond = (long) sampleRate * channels * bitsPerSample / 8;
        if (bytesPerSecond <= 0 || dataBytes % (channels * (bitsPerSample / 8L)) != 0) {
            throw new AudioValidationException(AudioValidationException.Reason.MALFORMED_WAV);
        }
        long durationNumerator = dataBytes * 1000L;
        long durationMs = durationNumerator / bytesPerSecond + (durationNumerator % bytesPerSecond == 0 ? 0 : 1);
        if (durationMs > limits.maxDurationMs()) {
            throw new AudioValidationException(AudioValidationException.Reason.DURATION_LIMIT);
        }
        return new WavPcmAudio(bytes.length, dataBytes, durationMs, sampleRate, channels, bitsPerSample);
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (bytes[offset + i] != (byte) value.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }
}
