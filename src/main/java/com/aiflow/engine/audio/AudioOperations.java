package com.aiflow.engine.audio;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Pure Java audio invocation policy shared by HTTP requests and workflow nodes. */
public final class AudioOperations {

    private final Policy policy;
    private final Supplier<AsrProvider> asrProviders;
    private final Supplier<TtsProvider> ttsProviders;

    public AudioOperations(Policy policy, Supplier<AsrProvider> asrProviders,
                           Supplier<TtsProvider> ttsProviders) {
        this.policy = Objects.requireNonNull(policy);
        this.asrProviders = Objects.requireNonNull(asrProviders);
        this.ttsProviders = Objects.requireNonNull(ttsProviders);
    }

    public AudioLimits limits() {
        return policy.limits();
    }

    public AsrResponse transcribe(AudioInput audio) {
        return transcribe(() -> audio);
    }

    /** The supplier is read only after the ASR capability gate has succeeded. */
    public AsrResponse transcribe(Supplier<AudioInput> input) {
        return transcribe(input, failure -> false);
    }

    public AsrResponse transcribe(Supplier<AudioInput> input,
                                  Predicate<RuntimeException> controlFailure) {
        AsrProvider provider = requireAsr(controlFailure);
        AudioInput audio = input == null ? null : input.get();
        if (audio == null) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        byte[] bytes = audio.bytes();
        WavPcmAudio wav = WavPcmValidator.validate(bytes, limits());
        String providerId = controlledMetadata(invoke(provider::id, controlFailure), 64);
        String model = controlledMetadata(policy.asr().model(), 128);
        AsrResponse response = invoke(() -> provider.transcribe(new AsrRequest(audio, model)), controlFailure);
        if (response == null || response.text().isBlank() || response.durationMs() != wav.durationMs()
                || !providerId.equals(controlledMetadata(response.provider(), 64))
                || !model.equals(controlledMetadata(response.model(), 128))) {
            throw invalidResponse();
        }
        return new AsrResponse(response.text(), providerId, model, wav.durationMs());
    }

    public TtsResponse synthesize(String text, String voice, String format) {
        return synthesize(text, voice, format, failure -> false);
    }

    public TtsResponse synthesize(String text, String voice, String format,
                                  Predicate<RuntimeException> controlFailure) {
        // Validate caller input before the capability gate. Disabled audio must still
        // report malformed/oversized text as a 4xx input error, rather than masking it
        // as a transient provider-unavailable result.
        TtsRequest request = new TtsRequest(text, voice, format, policy.tts().model());
        if (!"wav".equals(request.format()) && !"audio/wav".equals(request.format())) {
            throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_MEDIA);
        }
        TtsProvider provider = requireTts(controlFailure);
        String providerId = controlledMetadata(invoke(provider::id, controlFailure), 64);
        String model = controlledMetadata(policy.tts().model(), 128);
        TtsRequest providerRequest = new TtsRequest(request.text(), request.voice(), request.format(), model);
        TtsResponse response = invoke(() -> provider.synthesize(providerRequest), controlFailure);
        if (response == null) {
            throw invalidResponse();
        }
        WavPcmAudio wav;
        byte[] bytes = response.audioBytes();
        try {
            requireWavMediaType(response.contentType());
            wav = WavPcmValidator.validate(bytes, limits());
        } catch (AudioValidationException invalidOutput) {
            throw invalidResponse();
        }
        if (response.durationMs() != wav.durationMs()
                || !providerId.equals(controlledMetadata(response.provider(), 64))
                || !model.equals(controlledMetadata(response.model(), 128))) {
            throw invalidResponse();
        }
        return new TtsResponse(bytes, "audio/wav", providerId, model, wav.durationMs());
    }

    public TtsResponse synthesize(TtsRequest request) {
        if (request == null) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        return synthesize(request.text(), request.voice(), request.format());
    }

    private AsrProvider requireAsr(Predicate<RuntimeException> controlFailure) {
        if (!selected(policy.asr())) {
            throw unavailable();
        }
        try {
            AsrProvider provider = asrProviders.get();
            if (provider == null || !matchesConfiguredProvider(policy.asr(), provider.id()) || !provider.available()) {
                throw unavailable();
            }
            return provider;
        } catch (RuntimeException unavailableProvider) {
            if (controlFailure.test(unavailableProvider)) {
                throw unavailableProvider;
            }
            throw unavailable();
        }
    }

    private TtsProvider requireTts(Predicate<RuntimeException> controlFailure) {
        if (!selected(policy.tts())) {
            throw unavailable();
        }
        try {
            TtsProvider provider = ttsProviders.get();
            if (provider == null || !matchesConfiguredProvider(policy.tts(), provider.id()) || !provider.available()) {
                throw unavailable();
            }
            return provider;
        } catch (RuntimeException unavailableProvider) {
            if (controlFailure.test(unavailableProvider)) {
                throw unavailableProvider;
            }
            throw unavailable();
        }
    }

    private boolean selected(Channel channel) {
        if (!policy.enabled() || !channel.enabled() || channel.provider() == null) {
            return false;
        }
        String configured = channel.provider().trim().toLowerCase(Locale.ROOT);
        return (policy.mockMode() && "mock".equals(configured))
                || (!policy.mockMode() && "local".equals(configured));
    }

    private static boolean matchesConfiguredProvider(Channel channel, String actualId) {
        return actualId != null && channel.provider() != null
                && channel.provider().equalsIgnoreCase(actualId);
    }

    public static String requireWavMediaType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!"audio/wav".equals(normalized) && !"audio/x-wav".equals(normalized)
                && !"audio/wave".equals(normalized)) {
            throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_MEDIA);
        }
        return normalized;
    }

    private static String controlledMetadata(String value, int maxLength) {
        if (value == null || value.length() > maxLength || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw invalidResponse();
        }
        return value;
    }

    private static <T> T invoke(Supplier<T> operation, Predicate<RuntimeException> controlFailure) {
        try {
            return operation.get();
        } catch (AudioProviderException knownFailure) {
            throw new AudioProviderException(knownFailure.reason());
        } catch (AudioValidationException invalidOutput) {
            throw invalidResponse();
        } catch (RuntimeException unexpectedFailure) {
            if (controlFailure.test(unexpectedFailure)) {
                throw unexpectedFailure;
            }
            throw new AudioProviderException(AudioProviderException.Reason.CALL_FAILED);
        }
    }


    private static AudioProviderException unavailable() {
        return new AudioProviderException(AudioProviderException.Reason.UNAVAILABLE);
    }

    private static AudioProviderException invalidResponse() {
        return new AudioProviderException(AudioProviderException.Reason.INVALID_RESPONSE);
    }

    public record Channel(boolean enabled, String provider, String model) {
    }

    public record Policy(boolean enabled, boolean mockMode, Channel asr, Channel tts, AudioLimits limits) {
        public Policy {
            Objects.requireNonNull(asr);
            Objects.requireNonNull(tts);
            Objects.requireNonNull(limits);
        }
    }
}
