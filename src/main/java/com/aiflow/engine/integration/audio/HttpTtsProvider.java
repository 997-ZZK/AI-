package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AudioLimits;
import com.aiflow.engine.audio.AudioOperations;
import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.audio.TtsRequest;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.audio.WavPcmAudio;
import com.aiflow.engine.audio.WavPcmValidator;

import java.util.Objects;

/** 程序装配的同步 HTTP TTS 边界；厂商私有字段只存在于协议实现。 */
public final class HttpTtsProvider implements TtsProvider {

    private final String id;
    private final String defaultModel;
    private final AudioLimits limits;
    private final AudioHttpProtocol<TtsRequest, TtsResponse> protocol;
    private final AudioHttpTransport transport;

    public HttpTtsProvider(String id, String defaultModel, AudioLimits limits,
                           AudioHttpProtocol<TtsRequest, TtsResponse> protocol, AudioHttpTransport transport) {
        this.id = AudioHttpAdapters.metadata(id, 64);
        this.defaultModel = AudioHttpAdapters.metadata(defaultModel, 128);
        this.limits = Objects.requireNonNull(limits);
        this.protocol = Objects.requireNonNull(protocol);
        this.transport = Objects.requireNonNull(transport);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean available() {
        return AudioHttpAdapters.supported(protocol);
    }

    @Override
    public String description() {
        return "同步 HTTP TTS 适配边界（静态能力，不代表厂商联调通过）";
    }

    @Override
    public TtsResponse synthesize(TtsRequest request) {
        if (request == null) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        if (!"wav".equals(request.format()) && !"audio/wav".equals(request.format())) {
            throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_MEDIA);
        }
        String model = request.model() == null ? defaultModel : AudioHttpAdapters.metadata(request.model(), 128);
        TtsResponse response = AudioHttpAdapters.call(protocol, transport,
                new TtsRequest(request.text(), request.voice(), request.format(), model));
        try {
            AudioOperations.requireWavMediaType(response.contentType());
            WavPcmAudio wav = WavPcmValidator.validate(response.audioBytes(), limits);
            if (response.durationMs() != wav.durationMs() || !id.equals(response.provider())
                    || !model.equals(response.model())) {
                throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
            }
        } catch (AudioValidationException invalidOutput) {
            throw new AudioProviderException(AudioProviderException.Reason.INVALID_RESPONSE);
        }
        return response;
    }
}
