package com.aiflow.engine.audio;

import java.util.Arrays;
import java.util.Locale;

/**
 * 一次 ASR 输入。音频只在调用链内以内存字节传递，不把原件写入引擎状态或日志。
 */
public record AudioInput(byte[] bytes, String mimeType) {

    public AudioInput {
        if (bytes == null || bytes.length == 0) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
        mimeType = mimeType == null || mimeType.isBlank() ? "audio/wav" : mimeType.trim().toLowerCase(Locale.ROOT);
        if (!"audio/wav".equals(mimeType) && !"audio/x-wav".equals(mimeType) && !"audio/wave".equals(mimeType)) {
            throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_MEDIA);
        }
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public String toString() {
        return "AudioInput[byteLength=" + bytes.length + ", container=WAV]";
    }
}
