package com.aiflow.engine.config;

import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.AsrRequest;
import com.aiflow.engine.audio.AsrResponse;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.audio.TtsRequest;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.integration.audio.AudioHttpRequest;
import com.aiflow.engine.integration.audio.AudioHttpResponse;
import com.aiflow.engine.integration.audio.LocalAsrProtocol;
import com.aiflow.engine.integration.audio.LocalTtsProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** local 音频装配只验证厂商无关边界，不启动网络服务也不读取凭证。 */
class LocalAudioSidecarConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, AudioConfig.class);

    @Test
    void localModeWithoutProtocolBeanReportsUnavailableProviders() {
        runner.withPropertyValues(localProperties("http://127.0.0.1:19091", "/asr", "/tts"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AsrProvider.class).available()).isFalse();
                    assertThat(context.getBean(TtsProvider.class).available()).isFalse();
                });
    }

    @Test
    void localModeWithProtocolsAndLoopbackEndpointRegistersBothProviders() {
        runner.withUserConfiguration(ProtocolConfig.class)
                .withPropertyValues(localProperties("http://127.0.0.1:19091", "/asr", "/tts"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AsrProvider asr = context.getBean(AsrProvider.class);
                    TtsProvider tts = context.getBean(TtsProvider.class);
                    assertThat(asr.id()).isEqualTo("local");
                    assertThat(asr.available()).isTrue();
                    assertThat(tts.id()).isEqualTo("local");
                    assertThat(tts.available()).isTrue();
                });
    }

    @Test
    void remoteEndpointFailsClosedAndKeepsProviderUnavailable() {
        runner.withUserConfiguration(ProtocolConfig.class)
                .withPropertyValues(localProperties("https://sidecar.example.invalid", "/asr", "/tts"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AsrProvider.class).available()).isFalse();
                    assertThat(context.getBean(TtsProvider.class).available()).isFalse();
                });
    }

    @Test
    void multipleProtocolBeansFailClosedAsUnavailable() {
        runner.withUserConfiguration(ProtocolConfig.class, DuplicateProtocolConfig.class)
                .withPropertyValues(localProperties("http://127.0.0.1:19091", "/asr", "/tts"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AsrProvider.class).available()).isFalse();
                    assertThat(context.getBean(TtsProvider.class).available()).isFalse();
                });
    }

    @Test
    void endpointPreservesVersionedBasePath() {
        AudioSidecarProperties properties = new AudioSidecarProperties(true, "http://localhost:19091/v1",
                "/asr", "/tts", Duration.ofSeconds(2), Duration.ofSeconds(10), 1024, 1024, 1,
                Duration.ZERO, 1);

        assertThat(properties.endpoint(properties.asrPath())).hasToString("http://localhost:19091/v1/asr");
        assertThat(properties.endpoint("tts")).hasToString("http://localhost:19091/v1/tts");
        assertThat(properties.endpoint("/tts?token=private")).isNull();
    }

    private static String[] localProperties(String baseUrl, String asrPath, String ttsPath) {
        return new String[]{
                "aiflow.audio.enabled=true",
                "aiflow.audio.mode=local",
                "aiflow.audio.asr.enabled=true",
                "aiflow.audio.asr.provider=local",
                "aiflow.audio.tts.enabled=true",
                "aiflow.audio.tts.provider=local",
                "aiflow.audio.local.enabled=true",
                "aiflow.audio.local.base-url=" + baseUrl,
                "aiflow.audio.local.asr-path=" + asrPath,
                "aiflow.audio.local.tts-path=" + ttsPath
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AudioProperties.class, AudioSidecarProperties.class})
    static class PropertiesConfig {
    }

    @Configuration(proxyBeanMethods = false)
    static class ProtocolConfig {
        @Bean
        static LocalAsrProtocol localAsrProtocol() {
            return new LocalAsrProtocol() {
                @Override
                public AudioHttpRequest encode(AsrRequest request) {
                    return request("application/octet-stream", "application/json");
                }

                @Override
                public AsrResponse decode(AudioHttpResponse response, AsrRequest request) {
                    return null;
                }
            };
        }

        @Bean
        static LocalTtsProtocol localTtsProtocol() {
            return new LocalTtsProtocol() {
                @Override
                public AudioHttpRequest encode(TtsRequest request) {
                    return request("application/json", "audio/wav");
                }

                @Override
                public TtsResponse decode(AudioHttpResponse response, TtsRequest request) {
                    return null;
                }
            };
        }

        private static AudioHttpRequest request(String contentType, String accept) {
            return new AudioHttpRequest(URI.create("http://127.0.0.1:9/ignored"), contentType, accept,
                    Map.of(), new byte[]{1}, false);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateProtocolConfig {
        @Bean
        LocalAsrProtocol duplicateAsrProtocol() {
            return ProtocolConfig.localAsrProtocol();
        }

        @Bean
        LocalTtsProtocol duplicateTtsProtocol() {
            return ProtocolConfig.localTtsProtocol();
        }
    }
}
