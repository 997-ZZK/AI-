package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AsrRequest;
import com.aiflow.engine.audio.AsrResponse;
import com.aiflow.engine.audio.AudioInput;
import com.aiflow.engine.audio.AudioLimits;
import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.TtsRequest;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.audio.WavPcmValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class HttpAudioProviderTest {

    private static final String PROVIDER = "http-fixture";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AudioLimits LIMITS = AudioLimits.defaults();
    private HttpServer server;
    private URI base;
    private byte[] wav;

    @BeforeEach
    void startFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        wav = new MockTtsProvider().synthesize(new TtsRequest("demo", null, null, null)).audioBytes();
    }

    @AfterEach
    void stopFixture() {
        server.stop(0);
    }

    @Test
    void localJsonTtsAndMultipartAsrContractRoundtrip() throws IOException {
        AtomicReference<JsonNode> ttsPayload = new AtomicReference<>();
        AtomicReference<byte[]> asrPayload = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/fixture-tts", exchange -> {
            ttsPayload.set(JSON.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, wav.length);
            try (var output = exchange.getResponseBody()) {
                output.write(wav);
            }
        });
        server.createContext("/fixture-asr", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            asrPayload.set(exchange.getRequestBody().readAllBytes());
            byte[] result = JSON.writeValueAsBytes(Map.of("text", "synthetic transcript", "model", "fixture-asr",
                    "duration_ms", 100));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, result.length);
            try (var output = exchange.getResponseBody()) {
                output.write(result);
            }
        });
        AtomicInteger credentialLookups = new AtomicInteger();
        AudioHttpCredentials credentials = AudioHttpCredentials.bearer("AIFLOW_AUDIO_FIXTURE_KEY", name -> {
            assertThat(name).isEqualTo("AIFLOW_AUDIO_FIXTURE_KEY");
            credentialLookups.incrementAndGet();
            return "synthetic-only";
        });
        try (var transport = new JdkAudioHttpTransport(JdkAudioHttpTransport.Options.defaults())) {
            var tts = new HttpTtsProvider(PROVIDER, "fixture-tts", LIMITS, ttsProtocol(credentials), transport);
            var asr = new HttpAsrProvider(PROVIDER, "fixture-asr", LIMITS, asrProtocol(credentials), transport);
            assertThat(tts.available()).isTrue();
            assertThat(asr.available()).isTrue();
            assertThat(credentialLookups).hasValue(0);
            TtsResponse speech = tts.synthesize(new TtsRequest("fixture text", "fixture-voice", "wav", null));
            AsrResponse transcript = asr.transcribe(new AsrRequest(new AudioInput(speech.audioBytes(), "audio/wav"), null));
            assertThat(transcript.text()).isEqualTo("synthetic transcript");
            assertThat(transcript.model()).isEqualTo("fixture-asr");
            assertThat(transcript.provider()).isEqualTo(PROVIDER);
            assertThat(transcript.durationMs()).isEqualTo(100);
            assertThat(ttsPayload.get().path("input").textValue()).isEqualTo("fixture text");
            assertThat(ttsPayload.get().path("model").textValue()).isEqualTo("fixture-tts");
            assertThat(ttsPayload.get().path("voice").textValue()).isEqualTo("fixture-voice");
            assertThat(authorization.get()).isEqualTo("Bearer synthetic-only");
            assertThat(contentType.get()).isEqualTo("multipart/form-data; boundary=offline-audio-fixture");
            String multipartHeaders = new String(asrPayload.get(), 0,
                    asrPayload.get().length - wav.length - "\r\n--offline-audio-fixture--\r\n".length(), StandardCharsets.UTF_8);
            assertThat(multipartHeaders).contains("name=\"model\"", "fixture-asr", "name=\"file\"; filename=\"sample.wav\"",
                    "Content-Type: audio/wav");
            assertThat(credentialLookups).hasValue(2);
        }
    }

    @ParameterizedTest
    @EnumSource(value = AudioHttpProtocol.Kind.class, names = {"ASYNCHRONOUS", "WEBSOCKET"})
    void unsupportedProtocolKindsFailClosedWithoutEncodeOrNetwork(AudioHttpProtocol.Kind kind) {
        AtomicInteger encodes = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        AudioHttpProtocol<TtsRequest, TtsResponse> protocol = new AudioHttpProtocol<>() {
            @Override
            public Kind kind() { return kind; }
            @Override
            public AudioHttpRequest encode(TtsRequest request) { encodes.incrementAndGet(); return null; }
            @Override
            public TtsResponse decode(AudioHttpResponse response, TtsRequest request) { return null; }
        };
        var provider = new HttpTtsProvider(PROVIDER, "fixture-tts", LIMITS, protocol, request -> {
            sends.incrementAndGet();
            return null;
        });
        assertThat(provider.available()).isFalse();
        assertReason(() -> provider.synthesize(ttsRequest()), AudioProviderException.Reason.UNAVAILABLE);
        assertThat(encodes).hasValue(0);
        assertThat(sends).hasValue(0);
    }

    @Test
    void malformedJsonBecomesFixedInvalidResponseWithoutCause() {
        var provider = new HttpAsrProvider(PROVIDER, "fixture-asr", LIMITS, asrProtocol(fakeCredentials()),
                request -> new AudioHttpResponse(200, "application/json", "private-broken-json".getBytes(StandardCharsets.UTF_8)));
        assertReason(() -> provider.transcribe(asrRequest()), AudioProviderException.Reason.INVALID_RESPONSE);
    }

    @Test
    void decodedTtsStillRequiresValidWav() {
        var provider = new HttpTtsProvider(PROVIDER, "fixture-tts", LIMITS, ttsProtocol(fakeCredentials()),
                request -> new AudioHttpResponse(200, "audio/wav", new byte[]{1, 2, 3}));
        assertReason(() -> provider.synthesize(ttsRequest()), AudioProviderException.Reason.INVALID_RESPONSE);
    }

    @Test
    void validWavWithWrongMediaTypeIsRejected() {
        var provider = new HttpTtsProvider(PROVIDER, "fixture-tts", LIMITS, ttsProtocol(fakeCredentials()),
                request -> new AudioHttpResponse(200, "text/plain", wav));
        assertReason(() -> provider.synthesize(ttsRequest()), AudioProviderException.Reason.INVALID_RESPONSE);
    }

    @Test
    void asrResponseMustMatchInputDurationAndConfiguredModel() throws IOException {
        byte[] wrongDuration = JSON.writeValueAsBytes(Map.of("text", "fixture", "model", "fixture-asr", "duration_ms", 101));
        var provider = new HttpAsrProvider(PROVIDER, "fixture-asr", LIMITS, asrProtocol(fakeCredentials()),
                request -> new AudioHttpResponse(200, "application/json", wrongDuration));
        assertReason(() -> provider.transcribe(asrRequest()), AudioProviderException.Reason.INVALID_RESPONSE);
    }

    @Test
    void inputValidationPreventsTransportCall() {
        AtomicInteger sends = new AtomicInteger();
        AudioHttpTransport transport = request -> { sends.incrementAndGet(); return null; };
        var asr = new HttpAsrProvider(PROVIDER, "fixture-asr", LIMITS, asrProtocol(fakeCredentials()), transport);
        var tts = new HttpTtsProvider(PROVIDER, "fixture-tts", LIMITS, ttsProtocol(fakeCredentials()), transport);
        assertThatThrownBy(() -> asr.transcribe(new AsrRequest(new AudioInput(new byte[1], "audio/wav"), null)))
                .isInstanceOf(AudioValidationException.class);
        assertThatThrownBy(() -> tts.synthesize(new TtsRequest("fixture", null, "mp3", null)))
                .isInstanceOf(AudioValidationException.class);
        assertThat(sends).hasValue(0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "private\r\nheader", "private key"})
    void credentialResolverFailsClosedBeforeSending(String value) {
        AtomicInteger sends = new AtomicInteger();
        AudioHttpCredentials credentials = AudioHttpCredentials.bearer("AIFLOW_AUDIO_FIXTURE_KEY", name -> value);
        var provider = new HttpTtsProvider(PROVIDER, "fixture-tts", LIMITS, ttsProtocol(credentials), request -> {
            sends.incrementAndGet();
            return null;
        });
        assertReason(() -> provider.synthesize(ttsRequest()), AudioProviderException.Reason.UNAVAILABLE);
        assertThat(sends).hasValue(0);
        assertThat(credentials.toString()).isEqualTo("AudioHttpCredentials[environmentName=AIFLOW_AUDIO_FIXTURE_KEY]");
    }

    @Test
    void credentialResolverExceptionIsSanitized() {
        AudioHttpCredentials credentials = AudioHttpCredentials.bearer("AIFLOW_AUDIO_FIXTURE_KEY", name -> {
            throw new IllegalStateException("private-credential-value");
        });
        assertReason(credentials::get, AudioProviderException.Reason.UNAVAILABLE);
    }

    @Test
    void arbitraryTransportExceptionCannotEscapeWithBody() {
        var provider = new HttpTtsProvider(PROVIDER, "fixture-tts", LIMITS, ttsProtocol(fakeCredentials()), request -> {
            throw new IllegalStateException("private-provider-response");
        });
        assertReason(() -> provider.synthesize(ttsRequest()), AudioProviderException.Reason.CALL_FAILED);
    }

    /** 这些 JSON 字段仅属于离线 fixture，不是任何真实厂商协议。 */
    private AudioHttpProtocol<TtsRequest, TtsResponse> ttsProtocol(AudioHttpCredentials credentials) {
        return new AudioHttpProtocol<>() {
            @Override
            public AudioHttpRequest encode(TtsRequest request) {
                try {
                    return new AudioHttpRequest(base.resolve("/fixture-tts"), "application/json", "audio/wav", credentials.get(),
                            JSON.writeValueAsBytes(Map.of("input", request.text(), "voice", request.voice(), "model", request.model())), false);
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            @Override
            public TtsResponse decode(AudioHttpResponse response, TtsRequest request) {
                return new TtsResponse(response.body(), response.contentType(), PROVIDER, request.model(), 100);
            }
        };
    }

    /** 固定文件名、边界和字段，模拟 multipart 适配边界，无音频 base64 或持久化。 */
    private AudioHttpProtocol<AsrRequest, AsrResponse> asrProtocol(AudioHttpCredentials credentials) {
        return new AudioHttpProtocol<>() {
            @Override
            public AudioHttpRequest encode(AsrRequest request) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                body.writeBytes(("--offline-audio-fixture\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                        + request.model() + "\r\n--offline-audio-fixture\r\nContent-Disposition: form-data; "
                        + "name=\"file\"; filename=\"sample.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                body.writeBytes(request.audio().bytes());
                body.writeBytes("\r\n--offline-audio-fixture--\r\n".getBytes(StandardCharsets.UTF_8));
                return new AudioHttpRequest(base.resolve("/fixture-asr"), "multipart/form-data; boundary=offline-audio-fixture",
                        "application/json", credentials.get(), body.toByteArray(), false);
            }
            @Override
            public AsrResponse decode(AudioHttpResponse response, AsrRequest request) {
                try {
                    if (!"application/json".equals(response.contentType())) {
                        throw new IllegalArgumentException();
                    }
                    JsonNode root = JSON.readTree(response.body());
                    if (root == null || !root.path("text").isTextual() || !root.path("model").isTextual()
                            || !root.path("duration_ms").isIntegralNumber()) {
                        throw new IllegalArgumentException();
                    }
                    return new AsrResponse(root.get("text").textValue(), PROVIDER, root.get("model").textValue(),
                            root.get("duration_ms").longValue());
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
    }

    private AudioHttpCredentials fakeCredentials() {
        return AudioHttpCredentials.bearer("AIFLOW_AUDIO_FIXTURE_KEY", name -> "synthetic-only");
    }

    private AsrRequest asrRequest() {
        assertThat(WavPcmValidator.validate(wav).durationMs()).isEqualTo(100);
        return new AsrRequest(new AudioInput(wav, "audio/wav"), null);
    }

    private TtsRequest ttsRequest() {
        return new TtsRequest("fixture", null, null, null);
    }

    private static void assertReason(Runnable action, AudioProviderException.Reason reason) {
        AudioProviderException failure = catchThrowableOfType(action::run, AudioProviderException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.toString()).doesNotContain("private", "synthetic-only", "fixture text");
    }
}
