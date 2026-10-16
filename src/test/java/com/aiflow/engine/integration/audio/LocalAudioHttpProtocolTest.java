package com.aiflow.engine.integration.audio;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAudioHttpProtocolTest {

    @Test
    void wrapperPinsEncodedRequestToConfiguredLoopbackEndpoint() {
        AudioHttpProtocol<String, String> delegate = new AudioHttpProtocol<>() {
            @Override
            public AudioHttpRequest encode(String request) {
                return new AudioHttpRequest(URI.create("http://127.0.0.1:9/ignored"),
                        "application/json", "application/json", Map.of("X-Test", "fixture"),
                        request.getBytes(), true);
            }

            @Override
            public String decode(AudioHttpResponse response, String request) {
                return request + ":" + response.status();
            }
        };
        LocalAudioHttpProtocol<String, String> protocol = new LocalAudioHttpProtocol<>(
                URI.create("http://localhost:19091/v1/asr"), delegate);

        AudioHttpRequest encoded = protocol.encode("payload");

        assertThat(encoded.endpoint()).hasToString("http://localhost:19091/v1/asr");
        assertThat(encoded.headers()).containsEntry("X-Test", "fixture");
        assertThat(encoded.body()).isEqualTo("payload".getBytes());
        assertThat(protocol.decode(new AudioHttpResponse(200, "application/json", new byte[0]), "payload"))
                .isEqualTo("payload:200");
    }

    @Test
    void missingEndpointFailsBeforeDelegateOrTransportUse() {
        AudioHttpProtocol<String, String> delegate = new AudioHttpProtocol<>() {
            @Override
            public AudioHttpRequest encode(String request) {
                throw new AssertionError("delegate must not be called");
            }

            @Override
            public String decode(AudioHttpResponse response, String request) {
                return "unused";
            }
        };
        LocalAudioHttpProtocol<String, String> protocol = new LocalAudioHttpProtocol<>(null, delegate);

        assertThat(protocol.kind()).isEqualTo(AudioHttpProtocol.Kind.ASYNCHRONOUS);
        assertThatThrownBy(() -> protocol.encode("payload"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("local audio sidecar endpoint unavailable");
    }
}
