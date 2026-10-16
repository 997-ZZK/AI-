package com.aiflow.engine.config;

import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.AudioHandleStore;
import com.aiflow.engine.audio.AudioOperations;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.integration.audio.MockAsrProvider;
import com.aiflow.engine.integration.audio.MockTtsProvider;
import com.aiflow.engine.integration.audio.HttpAsrProvider;
import com.aiflow.engine.integration.audio.HttpTtsProvider;
import com.aiflow.engine.integration.audio.JdkAudioHttpTransport;
import com.aiflow.engine.integration.audio.LocalAsrProtocol;
import com.aiflow.engine.integration.audio.LocalAudioHttpProtocol;
import com.aiflow.engine.integration.audio.LocalTtsProtocol;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

/** 音频 provider 装配：mock 与本地 sidecar 都必须显式选择，默认不出网。 */
@Configuration
@EnableConfigurationProperties(AudioSidecarProperties.class)
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
        return new AudioOperations(new AudioOperations.Policy(properties.enabled() && !properties.disabledMode()
                && (properties.mockMode() || properties.localMode()),
                properties.mockMode(),
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

    /**
     * 本地 sidecar 只在显式 local 模式、通道 provider=local 且提供协议 bean 时装配。
     * 协议 bean 决定请求/响应字段；这里仅固定 loopback endpoint 和 HTTP 传输限制。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "aiflow.audio", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "aiflow.audio", name = "mode", havingValue = "local")
    @ConditionalOnProperty(prefix = "aiflow.audio.local", name = "enabled", havingValue = "true")
    public JdkAudioHttpTransport localAudioHttpTransport(AudioSidecarProperties properties) {
        return new JdkAudioHttpTransport(properties.transportOptions());
    }

    @Bean
    @ConditionalOnProperty(prefix = "aiflow.audio", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "aiflow.audio", name = "mode", havingValue = "local")
    @ConditionalOnProperty(prefix = "aiflow.audio.local", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "aiflow.audio.asr", name = "provider", havingValue = "local")
    @ConditionalOnProperty(prefix = "aiflow.audio.asr", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AsrProvider localAsrProvider(AudioProperties properties, AudioSidecarProperties sidecar,
                                        ObjectProvider<LocalAsrProtocol> protocols,
                                        JdkAudioHttpTransport transport) {
        return new HttpAsrProvider("local", properties.asr().model(), properties.limits().toAudioLimits(),
                new LocalAudioHttpProtocol<>(sidecar.endpoint(sidecar.asrPath()), protocols.getIfUnique()), transport);
    }

    @Bean
    @ConditionalOnProperty(prefix = "aiflow.audio", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "aiflow.audio", name = "mode", havingValue = "local")
    @ConditionalOnProperty(prefix = "aiflow.audio.local", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "aiflow.audio.tts", name = "provider", havingValue = "local")
    @ConditionalOnProperty(prefix = "aiflow.audio.tts", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TtsProvider localTtsProvider(AudioProperties properties, AudioSidecarProperties sidecar,
                                        ObjectProvider<LocalTtsProtocol> protocols,
                                        JdkAudioHttpTransport transport) {
        return new HttpTtsProvider("local", properties.tts().model(), properties.limits().toAudioLimits(),
                new LocalAudioHttpProtocol<>(sidecar.endpoint(sidecar.ttsPath()), protocols.getIfUnique()), transport);
    }
}
