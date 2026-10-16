package com.aiflow.engine.config;

import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.audio.TtsRequest;
import com.aiflow.engine.integration.HttpEndpointFactory;
import com.aiflow.engine.integration.audio.MockAsrProvider;
import com.aiflow.engine.integration.audio.MockTtsProvider;
import com.aiflow.engine.provider.CapabilityRegistry;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.provider.mock.MockChatProvider;
import com.aiflow.engine.retrieval.mock.MockEmbeddingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 音频配置装配测试不启动 Web、数据库或真实 provider。 */
class AudioConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, AudioConfig.class);

    @Test
    void disabledRootDoesNotResolveOrProbeAssembledAudioPorts() {
        ObjectProvider<AsrProvider> asr = ports(null);
        ObjectProvider<TtsProvider> tts = ports(null);

        CapabilityRegistry.CapabilityReport report = registry(AudioProperties.defaults(), asr, tts).report();

        assertThat(channel(report, "asr").status()).isEqualTo("unavailable");
        assertThat(channel(report, "tts").status()).isEqualTo("unavailable");
        verifyNoInteractions(asr, tts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "http"})
    void nonMockModesDoNotResolveOrProbeAudioPorts(String mode) {
        ObjectProvider<AsrProvider> asr = ports(null);
        ObjectProvider<TtsProvider> tts = ports(null);
        AudioProperties properties = new AudioProperties(true, mode, null, null, null);

        CapabilityRegistry.CapabilityReport report = registry(properties, asr, tts).report();

        assertThat(channel(report, "asr").status()).isEqualTo("unavailable");
        assertThat(channel(report, "tts").status()).isEqualTo("unavailable");
        verifyNoInteractions(asr, tts);
    }

    @Test
    void disabledChannelSkipsItsOwnPortAndKeepsTheOtherChannelAvailable() {
        ObjectProvider<AsrProvider> asr = ports(null);
        ObjectProvider<TtsProvider> tts = ports(new MockTtsProvider());
        AudioProperties properties = new AudioProperties(true, "mock",
                new AudioProperties.Asr(false, "mock", "mock-asr-1", null), null, null);

        CapabilityRegistry.CapabilityReport report = registry(properties, asr, tts).report();

        assertThat(channel(report, "asr").status()).isEqualTo("unavailable");
        assertThat(channel(report, "tts").status()).isEqualTo("mock");
        verifyNoInteractions(asr);
        verify(tts).getIfAvailable();
    }

    @Test
    void nonMockConfiguredProvidersDoNotResolveAudioPorts() {
        ObjectProvider<AsrProvider> asr = ports(null);
        ObjectProvider<TtsProvider> tts = ports(null);
        AudioProperties properties = new AudioProperties(true, "mock",
                new AudioProperties.Asr(true, "bailian", "model", null),
                new AudioProperties.Tts(true, "bailian", "model", null), null);

        CapabilityRegistry.CapabilityReport report = registry(properties, asr, tts).report();

        assertThat(channel(report, "asr").status()).isEqualTo("unavailable");
        assertThat(channel(report, "tts").status()).isEqualTo("unavailable");
        verifyNoInteractions(asr, tts);
    }

    @Test
    void beanResolutionFailureOnlyMakesItsChannelUnavailableWithoutExternalDetails() {
        ObjectProvider<AsrProvider> asr = ports(null);
        when(asr.getIfAvailable()).thenThrow(new IllegalStateException("private-bean-resolution-marker"));
        CapabilityRegistry.CapabilityReport report = registry(new AudioProperties(true, "mock", null, null, null),
                asr, ports(new MockTtsProvider())).report();

        assertProbeFailureIsolated(report, "asr", "tts", "private-bean-resolution-marker");
    }

    @Test
    void providerIdFailureOnlyMakesItsChannelUnavailableAndSkipsAvailability() {
        TtsProvider tts = mock(TtsProvider.class);
        when(tts.id()).thenThrow(new IllegalStateException("private-provider-id-marker"));
        CapabilityRegistry.CapabilityReport report = registry(new AudioProperties(true, "mock", null, null, null),
                ports(new MockAsrProvider()), ports(tts)).report();

        assertProbeFailureIsolated(report, "tts", "asr", "private-provider-id-marker");
        verify(tts, never()).available();
    }

    @Test
    void availabilityFailureOnlyMakesItsChannelUnavailableWithoutExternalDetails() {
        AsrProvider asr = mock(AsrProvider.class);
        when(asr.id()).thenReturn("mock");
        when(asr.available()).thenThrow(new IllegalStateException("private-availability-marker"));
        CapabilityRegistry.CapabilityReport report = registry(new AudioProperties(true, "mock", null, null, null),
                ports(asr), ports(new MockTtsProvider())).report();

        assertProbeFailureIsolated(report, "asr", "tts", "private-availability-marker");
    }

    @Test
    void mismatchedActualProviderIdCannotClaimMockAndSkipsAvailability() {
        AsrProvider asr = mock(AsrProvider.class);
        when(asr.id()).thenReturn("private-unexpected-provider-marker");
        CapabilityRegistry.CapabilityReport report = registry(new AudioProperties(true, "mock", null, null, null),
                ports(asr), ports(new MockTtsProvider())).report();

        assertProbeFailureIsolated(report, "asr", "tts", "private-unexpected-provider-marker");
        verify(asr, never()).available();
    }

    @Test
    void ambiguousAsrBeansDoNotBreakTtsOrTheRestOfTheCapabilityReport() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
                .withBean("firstAsr", AsrProvider.class, MockAsrProvider::new)
                .withBean("secondAsr", AsrProvider.class, MockAsrProvider::new)
                .withBean(TtsProvider.class, MockTtsProvider::new)
                .withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.mode=mock")
                .run(context -> {
                    CapabilityRegistry.CapabilityReport report = registry(context.getBean(AudioProperties.class),
                            context.getBeanFactory().getBeanProvider(AsrProvider.class),
                            context.getBeanFactory().getBeanProvider(TtsProvider.class)).report();
                    assertProbeFailureIsolated(report, "asr", "tts", "firstAsr");
                    assertThat(report.toString()).doesNotContain("secondAsr");
                });
    }

    @Test
    void defaultsAreDisabledAndBindIndependentLimits() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(AsrProvider.class).doesNotHaveBean(TtsProvider.class);
            AudioProperties properties = context.getBean(AudioProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.mode()).isEqualTo("mock");
            assertThat(properties.asr().model()).isEqualTo("mock-asr-1");
            assertThat(properties.tts().model()).isEqualTo("mock-tts-1");
            assertThat(properties.limits().maxDuration()).isEqualTo(Duration.ofSeconds(30));
            assertThat(channel(context, "asr").status()).isEqualTo("unavailable");
            assertThat(channel(context, "tts").candidates().getFirst().detail())
                    .contains("aiflow.audio.enabled=false");
        });
    }

    @Test
    void rootDisabledWinsOverEnabledChannels() {
        runner.withPropertyValues("aiflow.audio.enabled=false", "aiflow.audio.mode=mock",
                "aiflow.audio.asr.enabled=true", "aiflow.audio.tts.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(AsrProvider.class).doesNotHaveBean(TtsProvider.class);
            assertThat(channel(context, "asr").status()).isEqualTo("unavailable");
        });
    }

    @Test
    void channelSwitchesAreIndependent() {
        runner.withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.asr.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(AsrProvider.class).hasSingleBean(TtsProvider.class);
                    assertThat(channel(context, "asr").status()).isEqualTo("unavailable");
                    assertThat(channel(context, "asr").candidates().getFirst().detail())
                            .contains("aiflow.audio.asr.enabled=false");
                    assertThat(channel(context, "tts").status()).isEqualTo("mock");
                });
        runner.withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.tts.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AsrProvider.class).doesNotHaveBean(TtsProvider.class);
                    assertThat(channel(context, "tts").status()).isEqualTo("unavailable");
                });
    }

    @Test
    void disabledOrUnsupportedModeDoesNotCreateProviders() {
        runner.withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.mode=disabled")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(AsrProvider.class).doesNotHaveBean(TtsProvider.class);
                    assertThat(channel(context, "asr").candidates().getFirst().detail()).contains("mode=disabled");
                });
        runner.withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.mode=http")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(AsrProvider.class).doesNotHaveBean(TtsProvider.class);
                    assertThat(channel(context, "asr").status()).isEqualTo("unavailable");
                    assertThat(channel(context, "asr").candidates().getFirst().detail())
                            .contains("未配置可用").contains("AIFLOW_AUDIO_ASR_API_KEY");
                });
    }

    @Test
    void nonMockProviderCannotUseMockAssembly() {
        runner.withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.asr.provider=bailian",
                "aiflow.audio.tts.provider=bailian").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(AsrProvider.class).doesNotHaveBean(TtsProvider.class);
            assertThat(channel(context, "asr").status()).isEqualTo("unavailable");
            assertThat(channel(context, "asr").candidates().getFirst().detail()).contains("provider=mock");
        });
    }

    @Test
    void enabledMockHasProvidersAndUsesBoundLimitsAndModel() {
        runner.withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.mode=mock",
                "aiflow.audio.asr.model=demo-asr", "aiflow.audio.tts.model=demo-tts",
                "aiflow.audio.limits.max-bytes=10000", "aiflow.audio.limits.max-duration=10s",
                "aiflow.audio.limits.sample-rate=16000", "aiflow.audio.limits.channels=1",
                "aiflow.audio.limits.bits-per-sample=16").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AsrProvider.class).hasSingleBean(TtsProvider.class);
            assertThat(context.getBean(AsrProvider.class)).isInstanceOf(MockAsrProvider.class);
            assertThat(context.getBean(TtsProvider.class)).isInstanceOf(MockTtsProvider.class);
            AudioProperties properties = context.getBean(AudioProperties.class);
            assertThat(properties.limits().toAudioLimits().maxBytes()).isEqualTo(10000);
            assertThat(properties.limits().toAudioLimits().maxDurationMs()).isEqualTo(10000);
            assertThat(context.getBean(TtsProvider.class).synthesize(new TtsRequest("样本", null, null, null)).model())
                    .isEqualTo("demo-tts");
            assertThat(channel(context, "asr").status()).isEqualTo("mock");
            assertThat(channel(context, "asr").candidates().getFirst().model()).isEqualTo("demo-asr");
            assertThat(channel(context, "tts").activeProvider()).isEqualTo("mock");
        });
    }

    @Test
    void configuredLimitIsEnforcedByMockTts() {
        runner.withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.limits.max-bytes=100")
                .run(context -> assertThatThrownBy(() -> context.getBean(TtsProvider.class)
                        .synthesize(new TtsRequest("样本", null, null, null)))
                        .isInstanceOf(AudioValidationException.class).hasMessageContaining("大小限制"));
    }

    @Test
    void fixedMockTtsFormatCannotClaimAvailabilityForAnotherSampleRate() {
        assertTtsUnavailableForLimit("aiflow.audio.limits.sample-rate=8000");
    }

    @Test
    void mockTtsMinimumDurationMustFitConfiguredDuration() {
        assertTtsUnavailableForLimit("aiflow.audio.limits.max-duration=50ms");
    }

    @Test
    void mockTtsMinimumWavMustFitConfiguredBytes() {
        assertTtsUnavailableForLimit("aiflow.audio.limits.max-bytes=100");
    }

    @Test
    void configurationAloneDoesNotClaimAvailableAudioWithoutBeans() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.mode=mock")
                .run(context -> {
                    assertThat(channel(context, "asr").status()).isEqualTo("unavailable");
                    assertThat(channel(context, "tts").candidates().getFirst().detail()).contains("未装配可用");
                });
    }

    @Test
    void compatibilityCapabilityConstructorPreservesEnabledCloudKnowledgeBaseAssumption() {
        RetrievalProperties properties = cloudEnabledRetrievalProperties();
        CapabilityRegistry compatibility = registry(properties, AudioProperties.defaults(), ports(null), ports(null));
        CapabilityRegistry.Channel compatibilityChannel = channel(compatibility.report(), "cloud-knowledge-base");
        assertThat(compatibilityChannel.status()).isEqualTo("ok");

        CapabilityRegistry assembled = new CapabilityRegistry(
                new ProviderRouter(List.of(new MockChatProvider(ProviderProperties.defaults())),
                        ProviderProperties.defaults()),
                new MockEmbeddingProvider("mock-embedding", 1024),
                new HttpEndpointFactory(ProviderProperties.defaults(), new ObjectMapper()),
                properties, AudioProperties.defaults(), ports(null), ports(null), List.of());
        assertThat(channel(assembled.report(), "cloud-knowledge-base").status()).isEqualTo("unavailable");
    }

    @Test
    void unhealthyAssembledProviderIsUnavailable() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
                .withBean(AsrProvider.class, () -> new AsrProvider() {
                    @Override public String id() { return "mock"; }
                    @Override public boolean available() { return false; }
                    @Override public com.aiflow.engine.audio.AsrResponse transcribe(com.aiflow.engine.audio.AsrRequest request) {
                        throw new AssertionError("状态报告不应调用 transcribe");
                    }
                }).withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.mode=mock")
                .run(context -> {
                    assertThat(channel(context, "asr").status()).isEqualTo("unavailable");
                    assertThat(channel(context, "asr").candidates().getFirst().detail()).contains("当前不可用");
                });
    }

    @Test
    void rejectsCredentialValueInEnvironmentNameWithFixedError() {
        assertThatThrownBy(() -> new AudioProperties.Asr(true, "mock", "model", "invalid-key-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("音频凭证环境变量名格式无效");
        assertThatThrownBy(() -> new AudioProperties.Tts(true, "mock", "model", "Bearer secret value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("音频凭证环境变量名格式无效");
    }

    private void assertTtsUnavailableForLimit(String property) {
        runner.withPropertyValues("aiflow.audio.enabled=true", "aiflow.audio.mode=mock", property)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TtsProvider.class);
                    assertThat(context.getBean(TtsProvider.class).available()).isFalse();
                    CapabilityRegistry.Channel tts = channel(context, "tts");
                    assertThat(tts.status()).isEqualTo("unavailable");
                    assertThat(tts.activeProvider()).isNull();
                    assertThat(tts.candidates().getFirst().available()).isFalse();
                    assertThat(tts.candidates().getFirst().detail()).contains("当前不可用");
                });
    }

    private static CapabilityRegistry.Channel channel(AssertableApplicationContext context, String name) {
        return channel(registry(context.getBean(AudioProperties.class),
                context.getBeanFactory().getBeanProvider(AsrProvider.class),
                context.getBeanFactory().getBeanProvider(TtsProvider.class)).report(), name);
    }

    private static CapabilityRegistry registry(AudioProperties properties, ObjectProvider<AsrProvider> asr,
                                                ObjectProvider<TtsProvider> tts) {
        return registry(RetrievalProperties.defaults(), properties, asr, tts);
    }

    private static CapabilityRegistry registry(RetrievalProperties retrievalProperties, AudioProperties properties,
                                                ObjectProvider<AsrProvider> asr, ObjectProvider<TtsProvider> tts) {
        ProviderProperties provider = ProviderProperties.defaults();
        ProviderRouter router = new ProviderRouter(List.of(new MockChatProvider(provider)), provider);
        return new CapabilityRegistry(router, new MockEmbeddingProvider("mock-embedding", 1024),
                new HttpEndpointFactory(provider, new ObjectMapper()), retrievalProperties,
                properties, asr, tts);
    }

    private static RetrievalProperties cloudEnabledRetrievalProperties() {
        RetrievalProperties defaults = RetrievalProperties.defaults();
        RetrievalProperties.Cloud cloud = new RetrievalProperties.Cloud(true, "http", "cloud", 0, 100,
                "http://localhost:8000/v1", "", "default", 4_000);
        return new RetrievalProperties(defaults.minScore(), defaults.topK(), defaults.minK(), defaults.maxK(),
                defaults.cliffRatio(), defaults.mmrLambda(), defaults.mergeAdjacent(), defaults.sources(),
                defaults.embeddingStore(), defaults.embedding(), cloud, defaults.ann());
    }

    private static CapabilityRegistry.Channel channel(CapabilityRegistry.CapabilityReport report, String name) {
        return report.channels().stream().filter(channel -> name.equals(channel.channel()))
                .findFirst().orElseThrow();
    }

    private static void assertProbeFailureIsolated(CapabilityRegistry.CapabilityReport report, String failedChannel,
                                                   String healthyChannel, String privateMarker) {
        CapabilityRegistry.Channel failed = channel(report, failedChannel);
        assertThat(failed.status()).isEqualTo("unavailable");
        assertThat(failed.activeProvider()).isNull();
        assertThat(failed.candidates().getFirst().available()).isFalse();
        assertThat(failed.candidates().getFirst().detail()).contains("当前不可用");
        assertThat(channel(report, healthyChannel).status()).isEqualTo("mock");
        assertThat(report.level()).isEqualTo("L3");
        assertThat(report.toString()).doesNotContain(privateMarker);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> ports(T provider) {
        ObjectProvider<T> ports = mock(ObjectProvider.class);
        when(ports.getIfAvailable()).thenReturn(provider);
        return ports;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AudioProperties.class)
    static class PropertiesConfig {
    }
}
