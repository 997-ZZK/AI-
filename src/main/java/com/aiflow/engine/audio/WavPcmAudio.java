package com.aiflow.engine.audio;

/** 已校验 WAV PCM 的头部元数据。 */
public record WavPcmAudio(long byteLength, long dataBytes, long durationMs,
                          int sampleRate, int channels, int bitsPerSample) {
}
