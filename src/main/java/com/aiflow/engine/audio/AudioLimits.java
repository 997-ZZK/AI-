package com.aiflow.engine.audio;

import java.time.Duration;

/** 本地音频护栏；最终生效限制取它与厂商限制中的较严者。 */
public final class AudioLimits {

    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    public static final Duration DEFAULT_MAX_DURATION = Duration.ofSeconds(30);
    public static final int DEFAULT_SAMPLE_RATE = 16_000;
    public static final int DEFAULT_CHANNELS = 1;
    public static final int DEFAULT_BITS_PER_SAMPLE = 16;

    private final long maxBytes;
    private final Duration maxDuration;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;

    public AudioLimits(long maxBytes, Duration maxDuration, int sampleRate, int channels, int bitsPerSample) {
        if (maxBytes <= 0 || maxDuration == null || maxDuration.isZero() || maxDuration.isNegative()
                || sampleRate <= 0 || sampleRate > 192_000 || channels <= 0 || channels > 2
                || (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32)) {
            throw new IllegalArgumentException("音频限制不符合支持的 PCM 参数");
        }
        try {
            if (maxDuration.toMillis() <= 0) {
                throw new IllegalArgumentException("音频时长限制至少为 1 ms");
            }
        } catch (ArithmeticException invalidDuration) {
            throw new IllegalArgumentException("音频时长限制过大");
        }
        this.maxBytes = maxBytes;
        this.maxDuration = maxDuration;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
    }

    public AudioLimits(long maxBytes, long maxDurationMs, int sampleRate, int channels, int bitsPerSample) {
        this(maxBytes, Duration.ofMillis(maxDurationMs), sampleRate, channels, bitsPerSample);
    }

    public static AudioLimits defaults() {
        return new AudioLimits(DEFAULT_MAX_BYTES, DEFAULT_MAX_DURATION, DEFAULT_SAMPLE_RATE,
                DEFAULT_CHANNELS, DEFAULT_BITS_PER_SAMPLE);
    }

    public long maxBytes() {
        return maxBytes;
    }

    public Duration maxDuration() {
        return maxDuration;
    }

    public long maxDurationMs() {
        return maxDuration.toMillis();
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public int bitsPerSample() {
        return bitsPerSample;
    }
}
