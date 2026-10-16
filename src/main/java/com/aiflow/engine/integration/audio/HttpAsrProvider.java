package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.AsrRequest;
import com.aiflow.engine.audio.AsrResponse;
import com.aiflow.engine.audio.AudioLimits;
import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.WavPcmAudio;
import com.aiflow.engine.audio.WavPcmValidator;

import java.util.Objects;

/** 程序装配的同步 HTTP ASR 边界；当前不注册为 D1/D2 可激活的 bean。 */
public final class HttpAsrProvider implements AsrProvider {

    private final String id;
    private final String defaultModel;
    private final AudioLimits limits;
    private final AudioHttpProtocol<AsrRequest, AsrResponse> protocol;
    private final AudioHttpTransport transport;

    public HttpAsrProvider(String id, String defaultModel, AudioLimits limits,
                           AudioHttpProtocol<AsrRequest, AsrResponse> protocol, AudioHttpTransport transport) {
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
        return "同步 HTTP ASR 适配边界（静态能力，不代表厂商联调通过）";
    }

    @Override
    public AsrResponse transcribe(AsrRequest request) {
        if (request == null) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        WavPcmAudio wav = WavPcmValidator.validate(request.audio().bytes(), limits);
        String model = request.model() == null ? defaultModel : AudioHttpAdapters.metadata(request.model(), 128);
        AsrResponse response = AudioHttpAdapters.call(protocol, transport, new AsrRequest(request.audio(), model));
        if (response.text().isBlank() || response.durationMs() != wav.durationMs()
                || !id.equals(response.provider()) || !model.equals(response.model())) {
            throw new AudioProviderException(AudioProviderException.Reason.INVALID_RESPONSE);
        }
        return response;
    }
}
