package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.AsrRequest;
import com.aiflow.engine.audio.AsrResponse;
import com.aiflow.engine.audio.AudioInput;
import com.aiflow.engine.audio.AudioLimits;
import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.audio.TtsRequest;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.audio.WavPcmValidator;
import com.aiflow.engine.config.AudioConfig;
import com.aiflow.engine.config.AudioProperties;
import com.aiflow.engine.config.AudioSidecarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * M5-B 的本地 sidecar loopback 验证：配置装配、endpoint 固定、JDK HTTP transport
 * 和本地协议编码/解码一起走真实请求，但只访问本机 HttpServer fixture。
 */
class LocalAudioSidecarLoopbackIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AudioLimits LIMITS = AudioLimits.defaults();
    private HttpServer server;
    private URI base;
    private byte[] wav;
    private long wavDurationMs;

    @BeforeEach
    void startFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        wav = new MockTtsProvider().synthesize(new TtsRequest("loopback", null, null, null)).audioBytes();
        wavDurationMs = WavPcmValidator.validate(wav).durationMs();
    }

    @AfterEach
    void stopFixture() {
        server.stop(0);
    }

    @Test
    void configuredLocalProvidersRoundTripThroughLoopbackTransport() throws IOException {
        AtomicReference<JsonNode> ttsRequest = new AtomicReference<>();
        AtomicReference<byte[]> asrRequest = new AtomicReference<>();
        AtomicInteger ttsCalls = new AtomicInteger();
        AtomicInteger asrCalls = new AtomicInteger();
        server.createContext("/v1/tts", exchange -> {
            ttsCalls.incrementAndGet();
            ttsRequest.set(JSON.readTree(exchange.getRequestBody()));
            reply(exchange, 200, "audio/wav", wav);
        });
        server.createContext("/v1/asr", exchange -> {
            asrCalls.incrementAndGet();
            asrRequest.set(exchange.getRequestBody().readAllBytes());
            byte[] body = JSON.writeValueAsBytes(Map.of("text", "loopback transcript", "duration_ms", wavDurationMs));
            reply(exchange, 200, "application/json", body);
        });

        newRunner().run(context -> {
            TtsProvider tts = context.getBean(TtsProvider.class);
            AsrProvider asr = context.getBean(AsrProvider.class);
            assertThat(tts.id()).isEqualTo("local");
            assertThat(asr.id()).isEqualTo("local");
            assertThat(tts.available()).isTrue();
            assertThat(asr.available()).isTrue();

            TtsResponse speech = tts.synthesize(new TtsRequest("hello sidecar", "fixture-voice", "wav", null));
            AsrResponse transcript = asr.transcribe(new AsrRequest(new AudioInput(speech.audioBytes(), "audio/wav"), null));

            assertThat(speech.audioBytes()).containsExactly(wav);
            assertThat(speech.provider()).isEqualTo("local");
            assertThat(speech.model()).isEqualTo("fixture-tts");
            assertThat(speech.durationMs()).isEqualTo(wavDurationMs);
            assertThat(transcript.text()).isEqualTo("loopback transcript");
            assertThat(transcript.provider()).isEqualTo("local");
            assertThat(transcript.model()).isEqualTo("fixture-asr");
            assertThat(transcript.durationMs()).isEqualTo(wavDurationMs);
        });

        assertThat(ttsCalls).hasValue(1);
        assertThat(asrCalls).hasValue(1);
        assertThat(ttsRequest.get().path("text").textValue()).isEqualTo("hello sidecar");
        assertThat(ttsRequest.get().path("voice").textValue()).isEqualTo("fixture-voice");
        assertThat(ttsRequest.get().path("model").textValue()).isEqualTo("fixture-tts");
        assertThat(asrRequest.get()).containsExactly(wav);
    }

    @Test
    void malformedLocalAsrResponseFailsClosedWithoutVendorBody() {
        server.createContext("/v1/asr", exchange -> reply(exchange, 200, "application/json",
                "sidecar-private-response".getBytes(StandardCharsets.UTF_8)));

        newRunner().run(context -> {
            AsrProvider asr = context.getBean(AsrProvider.class);
            AudioProviderException failure = catchThrowableOfType(
                    () -> asr.transcribe(new AsrRequest(new AudioInput(wav, "audio/wav"), null)),
                    AudioProviderException.class);

            assertThat(failure).isNotNull();
            assertThat(failure.reason()).isEqualTo(AudioProviderException.Reason.INVALID_RESPONSE);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.toString()).doesNotContain("sidecar-private-response");
        });
    }

    @Test
    void slowLocalTtsResponseMapsToFixedTimeout() {
        server.createContext("/v1/tts", exchange -> {
            try {
                Thread.sleep(500);
                reply(exchange, 200, "audio/wav", wav);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        runner("100ms").run(context -> {
            TtsProvider tts = context.getBean(TtsProvider.class);
            AudioProviderException failure = catchThrowableOfType(
                    () -> tts.synthesize(new TtsRequest("timeout", null, "wav", null)),
                    AudioProviderException.class);

            assertThat(failure).isNotNull();
            assertThat(failure.reason()).isEqualTo(AudioProviderException.Reason.TIMEOUT);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.toString()).doesNotContain("sidecar-private-response", "/v1/tts");
        });
    }

    private ApplicationContextRunner newRunner() {
        return runner("2s");
    }

    private ApplicationContextRunner runner(String callTimeout) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, ProtocolConfig.class, AudioConfig.class)
                .withPropertyValues(
                        "aiflow.audio.enabled=true",
                        "aiflow.audio.mode=local",
                        "aiflow.audio.asr.enabled=true",
                        "aiflow.audio.asr.provider=local",
                        "aiflow.audio.asr.model=fixture-asr",
                        "aiflow.audio.tts.enabled=true",
                        "aiflow.audio.tts.provider=local",
                        "aiflow.audio.tts.model=fixture-tts",
                        "aiflow.audio.local.enabled=true",
                        "aiflow.audio.local.base-url=" + base,
                        "aiflow.audio.local.asr-path=/asr",
                        "aiflow.audio.local.tts-path=/tts",
                        "aiflow.audio.local.call-timeout=" + callTimeout);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AudioProperties.class, AudioSidecarProperties.class})
    static class PropertiesConfig {
    }

    @Configuration(proxyBeanMethods = false)
    static class ProtocolConfig {
        @Bean
        LocalAsrProtocol localAsrProtocol() {
            return new LocalAsrProtocol() {
                @Override
                public AudioHttpRequest encode(AsrRequest request) {
                    return new AudioHttpRequest(URI.create("http://127.0.0.1:9/ignored"), "audio/wav",
                            "application/json", Map.of(), request.audio().bytes(), false);
                }

                @Override
                public AsrResponse decode(AudioHttpResponse response, AsrRequest request) {
                    try {
                        JsonNode body = JSON.readTree(response.body());
                        return new AsrResponse(body.path("text").textValue(), "local", request.model(),
                                body.path("duration_ms").longValue());
                    } catch (IOException | RuntimeException ignored) {
                        throw new IllegalStateException("invalid local ASR fixture response");
                    }
                }
            };
        }

        @Bean
        LocalTtsProtocol localTtsProtocol() {
            return new LocalTtsProtocol() {
                @Override
                public AudioHttpRequest encode(TtsRequest request) {
                    try {
                        byte[] body = JSON.writeValueAsBytes(Map.of("text", request.text(), "voice", request.voice(),
                                "model", request.model()));
                        return new AudioHttpRequest(URI.create("http://127.0.0.1:9/ignored"), "application/json",
                                "audio/wav", Map.of(), body, false);
                    } catch (IOException ignored) {
                        throw new IllegalStateException("unable to encode local TTS fixture request");
                    }
                }

                @Override
                public TtsResponse decode(AudioHttpResponse response, TtsRequest request) {
                    return new TtsResponse(response.body(), response.contentType(), "local", request.model(),
                            WavPcmValidator.validate(response.body()).durationMs());
                }
            };
        }
    }

    private static void reply(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
