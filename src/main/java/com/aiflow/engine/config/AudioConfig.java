package com.aiflow.engine.config;

import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.AudioHandleStore;
import com.aiflow.engine.audio.AudioOperations;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.integration.audio.MockAsrProvider;
import com.aiflow.engine.integration.audio.MockTtsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

/** D0 音频 provider 装配：只在明确开启 mock 音频时创建桩。 */
@Configuration
public class AudioConfig {

    @Bean
    public AudioHandleStore audioHandleStore() {
        return new AudioHandleStore();
    }

    @Bean
    public AudioHandleCleanupScheduler audioHandleCleanupScheduler(AudioHandleStore store) {
        return new AudioHandleCleanupScheduler(store);
    }

    @Bean
    public AudioOperations audioOperations(AudioProperties properties,
                                           ObjectProvider<AsrProvider> asrProviders,
                                           ObjectProvider<TtsProvider> ttsProviders) {
        return new AudioOperations(new AudioOperations.Policy(properties.enabled(), properties.mockMode(),
                new AudioOperations.Channel(properties.asr().enabled(), properties.asr().provider(), properties.asr().model()),
                new AudioOperations.Channel(properties.tts().enabled(), properties.tts().provider(), properties.tts().model()),
                properties.limits().toAudioLimits()), asrProviders::getIfAvailable, ttsProviders::getIfAvailable);
    }

    @Bean
    @ConditionalOnProperty(prefix = "aiflow.audio", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "aiflow.audio", name = "mode", havingValue = "mock", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "aiflow.audio.asr", name = "provider", havingValue = "mock", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "aiflow.audio.asr", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AsrProvider asrProvider(AudioProperties properties) {
        return new MockAsrProvider(properties.limits().toAudioLimits(), properties.asr().model());
    }

    @Bean
    @ConditionalOnProperty(prefix = "aiflow.audio", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "aiflow.audio", name = "mode", havingValue = "mock", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "aiflow.audio.tts", name = "provider", havingValue = "mock", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "aiflow.audio.tts", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TtsProvider ttsProvider(AudioProperties properties) {
        return new MockTtsProvider(properties.limits().toAudioLimits(), properties.tts().model());
    }
}
