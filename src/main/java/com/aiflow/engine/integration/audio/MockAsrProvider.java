package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.AsrRequest;
import com.aiflow.engine.audio.AsrResponse;
import com.aiflow.engine.audio.AudioLimits;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.WavPcmAudio;
import com.aiflow.engine.audio.WavPcmValidator;

/** 离线 ASR 契约桩：只验证输入链路，不代表真实语音识别质量。 */
public final class MockAsrProvider implements AsrProvider {

    private final AudioLimits limits;
    private final String defaultModel;

    public MockAsrProvider() {
        this(AudioLimits.defaults());
    }

    public MockAsrProvider(AudioLimits limits) {
        this(limits, "mock-asr-1");
    }

    public MockAsrProvider(AudioLimits limits, String defaultModel) {
        this.limits = limits == null ? AudioLimits.defaults() : limits;
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? "mock-asr-1" : defaultModel.trim();
    }

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public boolean available() {
        long frameBytes = (long) limits.channels() * limits.bitsPerSample() / 8;
        long minimumBytes = 44 + frameBytes + (frameBytes & 1L);
        long minimumDurationMs = 1000L / limits.sampleRate() + (1000L % limits.sampleRate() == 0 ? 0 : 1);
        return limits.maxBytes() >= minimumBytes && limits.maxDurationMs() >= minimumDurationMs;
    }

    @Override
    public String description() {
        return "离线 ASR 契约桩（只验证音频输入链路，不代表真实识别质量）";
    }

    @Override
    public AsrResponse transcribe(AsrRequest request) {
        if (request == null) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        WavPcmAudio wav = WavPcmValidator.validate(request.audio().bytes(), limits);
        String model = request.model() == null ? defaultModel : request.model();
        return new AsrResponse("（mock ASR）已收到音频，时长 " + wav.durationMs() + " ms。",
                id(), model, wav.durationMs());
    }
}
