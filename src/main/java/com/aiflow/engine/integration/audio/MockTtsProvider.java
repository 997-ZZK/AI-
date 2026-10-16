package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AudioLimits;
import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.audio.TtsRequest;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.audio.WavPcmAudio;
import com.aiflow.engine.audio.WavPcmValidator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 离线 TTS 契约桩：返回确定性的静音 WAV，不代表真实合成质量。 */
public final class MockTtsProvider implements TtsProvider {

    private static final int SAMPLE_RATE = AudioLimits.DEFAULT_SAMPLE_RATE;
    private static final int CHANNELS = AudioLimits.DEFAULT_CHANNELS;
    private static final int BITS_PER_SAMPLE = AudioLimits.DEFAULT_BITS_PER_SAMPLE;
    private static final int MIN_DURATION_MS = 100;
    private static final int MIN_WAV_BYTES = 3_244;

    private final AudioLimits limits;
    private final String defaultModel;

    public MockTtsProvider() {
        this(AudioLimits.defaults());
    }

    public MockTtsProvider(AudioLimits limits) {
        this(limits, "mock-tts-1");
    }

    public MockTtsProvider(AudioLimits limits, String defaultModel) {
        this.limits = limits == null ? AudioLimits.defaults() : limits;
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? "mock-tts-1" : defaultModel.trim();
    }

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public boolean available() {
        return supportsFixedFormat() && limits.maxDurationMs() >= MIN_DURATION_MS && limits.maxBytes() >= MIN_WAV_BYTES;
    }

    @Override
    public String description() {
        return "离线 TTS 契约桩（返回静音 WAV，不代表真实合成质量）";
    }

    @Override
    public TtsResponse synthesize(TtsRequest request) {
        if (request == null) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        if (!"wav".equals(request.format()) && !"audio/wav".equals(request.format())) {
            throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_MEDIA);
        }
        if (!supportsFixedFormat()) {
            throw new AudioProviderException(AudioProviderException.Reason.UNAVAILABLE);
        }
        int durationMs = Math.max(MIN_DURATION_MS,
                Math.min(2_000, request.text().codePointCount(0, request.text().length()) * 20));
        byte[] bytes = silenceWav(durationMs);
        WavPcmAudio wav = WavPcmValidator.validate(bytes, limits);
        return new TtsResponse(bytes, "audio/wav", id(), request.model() == null ? defaultModel : request.model(),
                wav.durationMs());
    }

    private boolean supportsFixedFormat() {
        return limits.sampleRate() == SAMPLE_RATE && limits.channels() == CHANNELS && limits.bitsPerSample() == BITS_PER_SAMPLE;
    }

    private byte[] silenceWav(int durationMs) {
        int dataLength = (int) Math.ceil(SAMPLE_RATE * (durationMs / 1000.0) * CHANNELS * BITS_PER_SAMPLE / 8.0);
        int paddedDataLength = dataLength + (dataLength & 1);
        ByteBuffer buffer = ByteBuffer.allocate(44 + paddedDataLength).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + paddedDataLength)
                .put(new byte[]{'W', 'A', 'V', 'E'})
                .put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1)
                .putShort((short) CHANNELS).putInt(SAMPLE_RATE)
                .putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
                .putShort((short) (CHANNELS * BITS_PER_SAMPLE / 8)).putShort((short) BITS_PER_SAMPLE)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLength)
                .put(new byte[paddedDataLength]);
        return buffer.array();
    }
}
