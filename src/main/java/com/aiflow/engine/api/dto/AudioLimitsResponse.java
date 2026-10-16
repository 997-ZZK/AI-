package com.aiflow.engine.api.dto;

import com.aiflow.engine.audio.AudioLimits;

/** 仅公开本地音频限制白名单，不携带配置、凭证或厂商响应原文。 */
public record AudioLimitsResponse(long maxBytes, long maxDurationMs, int sampleRate,
                                  int channels, int bitsPerSample) {

    public static AudioLimitsResponse from(AudioLimits limits) {
        return new AudioLimitsResponse(limits.maxBytes(), limits.maxDurationMs(), limits.sampleRate(),
                limits.channels(), limits.bitsPerSample());
    }
}
