package com.aiflow.engine.config;

import com.aiflow.engine.audio.AudioLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Locale;

/**
 * ASR/TTS 独立配置。
 *
 * <p>音频能力不复用 {@link ProviderProperties}：语音厂商的协议、计费和限制与聊天模型不同，
 * 也不能因为聊天 provider 开着就默认为语音可用。默认关闭；凭证只记录环境变量名，值由 provider
 * 在实际联调时从进程环境读取。</p>
 */
@ConfigurationProperties(prefix = "aiflow.audio")
public record AudioProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("mock") String mode,
        @DefaultValue Asr asr,
        @DefaultValue Tts tts,
        @DefaultValue Limits limits
) {

    public AudioProperties {
        mode = mode == null || mode.isBlank() ? "disabled" : mode.trim().toLowerCase(Locale.ROOT);
        asr = asr == null ? Asr.defaults() : asr;
        tts = tts == null ? Tts.defaults() : tts;
        limits = limits == null ? Limits.defaults() : limits;
    }

    public static AudioProperties defaults() {
        return new AudioProperties(false, "mock", Asr.defaults(), Tts.defaults(), Limits.defaults());
    }

    /** 当前配置是否明确选择 mock 档。*/
    public boolean mockMode() {
        return "mock".equals(mode);
    }

    /** 当前配置是否明确关闭音频能力。*/
    public boolean disabledMode() {
        return "disabled".equals(mode);
    }

    /** 当前配置是否明确选择本地 sidecar 档。 */
    public boolean localMode() {
        return "local".equals(mode);
    }

    public record Asr(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("mock") String provider,
            @DefaultValue("mock-asr-1") String model,
            @DefaultValue("AIFLOW_AUDIO_ASR_API_KEY") String apiKeyEnv
    ) {

        public Asr {
            provider = normalize(provider, "mock");
            model = normalize(model, "mock-asr-1");
            apiKeyEnv = normalizeEnv(apiKeyEnv, "AIFLOW_AUDIO_ASR_API_KEY");
        }

        public static Asr defaults() {
            return new Asr(true, "mock", "mock-asr-1", "AIFLOW_AUDIO_ASR_API_KEY");
        }
    }

    public record Tts(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("mock") String provider,
            @DefaultValue("mock-tts-1") String model,
            @DefaultValue("AIFLOW_AUDIO_TTS_API_KEY") String apiKeyEnv
    ) {

        public Tts {
            provider = normalize(provider, "mock");
            model = normalize(model, "mock-tts-1");
            apiKeyEnv = normalizeEnv(apiKeyEnv, "AIFLOW_AUDIO_TTS_API_KEY");
        }

        public static Tts defaults() {
            return new Tts(true, "mock", "mock-tts-1", "AIFLOW_AUDIO_TTS_API_KEY");
        }
    }

    /** 本地限制；provider 的最终限制必须取厂商协议和这里配置的较严者。*/
    public record Limits(
            @DefaultValue("5242880") long maxBytes,
            @DefaultValue("30s") Duration maxDuration,
            @DefaultValue("16000") int sampleRate,
            @DefaultValue("1") int channels,
            @DefaultValue("16") int bitsPerSample
    ) {

        public Limits {
            if (maxBytes <= 0 || maxDuration == null || maxDuration.isZero() || maxDuration.isNegative()
                    || sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) {
                throw new IllegalArgumentException("Invalid audio limits");
            }
        }

        public static Limits defaults() {
            return new Limits(AudioLimits.DEFAULT_MAX_BYTES, AudioLimits.DEFAULT_MAX_DURATION,
                    AudioLimits.DEFAULT_SAMPLE_RATE, AudioLimits.DEFAULT_CHANNELS,
                    AudioLimits.DEFAULT_BITS_PER_SAMPLE);
        }

        public AudioLimits toAudioLimits() {
            return new AudioLimits(maxBytes, maxDuration, sampleRate, channels, bitsPerSample);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizeEnv(String value, String fallback) {
        String normalized = normalize(value, fallback);
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("音频凭证环境变量名格式无效");
        }
        return normalized;
    }
}
