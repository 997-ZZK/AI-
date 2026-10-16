package com.aiflow.engine.audio;

/** 一次同步 ASR 请求。 */
public record AsrRequest(AudioInput audio, String model) {

    public AsrRequest {
        if (audio == null) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        model = model == null || model.isBlank() ? null : model.trim();
    }

    @Override
    public String toString() {
        return "AsrRequest[audio=" + audio + "]";
    }
}
