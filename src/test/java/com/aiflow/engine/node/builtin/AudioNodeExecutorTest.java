package com.aiflow.engine.node.builtin;

import com.aiflow.engine.audio.AudioHandleStore;
import com.aiflow.engine.audio.AudioOperations;
import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.config.AudioProperties;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.RunControl;
import com.aiflow.engine.integration.audio.MockAsrProvider;
import com.aiflow.engine.integration.audio.MockTtsProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioNodeExecutorTest {

    @Test
    void ttsAndAsrExchangeOnlyHandleAndTranscriptVariables() {
        AudioProperties properties = new AudioProperties(true, "mock",
                new AudioProperties.Asr(true, "mock", "mock-asr-1", "AIFLOW_AUDIO_ASR_API_KEY"),
                new AudioProperties.Tts(true, "mock", "mock-tts-1", "AIFLOW_AUDIO_TTS_API_KEY"),
                AudioProperties.Limits.defaults());
        AudioOperations operations = operations(properties);
        AudioHandleStore handles = new AudioHandleStore();
        UUID runId = UUID.randomUUID();
        handles.begin(runId);
        Map<String, Object> variables = new HashMap<>(Map.of("input", "hello"));
        TtsNodeExecutor tts = new TtsNodeExecutor(operations, handles);
        NodeResult speech = tts.execute(context(runId, node("tts", "tts.speak", Map.of()), variables));

        assertThat(speech.output()).containsKeys("audioHandle", "provider", "model", "durationMs", "byteLength");
        assertThat(variables).containsKey("audioHandle").doesNotContainKey("audioBytes");
        NodeResult transcript = new AsrNodeExecutor(operations, handles)
                .execute(context(runId, node("asr", "asr.transcribe", Map.of()), variables));
        assertThat(transcript.output()).containsKeys("transcript", "provider", "model", "durationMs");
        assertThat(String.valueOf(variables.get("transcript"))).startsWith("（mock ASR）");
        assertThat(transcript.output().values()).noneMatch(value -> value instanceof byte[]);
    }

    @Test
    void disabledCapabilityFailsWithFixedProviderErrorAndUnknownParametersAreRejected() {
        AudioProperties disabled = AudioProperties.defaults();
        AudioOperations operations = operations(disabled);
        AudioHandleStore handles = new AudioHandleStore();
        UUID runId = UUID.randomUUID();
        handles.begin(runId);
        assertThatThrownBy(() -> new TtsNodeExecutor(operations, handles)
                .execute(context(runId, node("tts", "tts.speak", Map.of("model", "caller")), new HashMap<>(Map.of("input", "x")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TtsNodeExecutor(operations, handles)
                .execute(context(runId, node("tts", "tts.speak", Map.of()), new HashMap<>(Map.of("input", "x")))))
                .isInstanceOf(AudioProviderException.class)
                .hasMessage("音频能力不可用");
    }

    @Test
    void runtimeRejectsNonWavFormatBeforeProviderInvocation() {
        AudioProperties properties = new AudioProperties(true, "mock", AudioProperties.Asr.defaults(),
                AudioProperties.Tts.defaults(), AudioProperties.Limits.defaults());
        AudioOperations operations = operations(properties);
        AudioHandleStore handles = new AudioHandleStore();
        UUID runId = UUID.randomUUID();
        handles.begin(runId);

        assertThatThrownBy(() -> new TtsNodeExecutor(operations, handles).execute(
                context(runId, node("tts", "tts.speak", Map.of("format", "mp3")),
                        new HashMap<>(Map.of("input", "x")))))
                .isInstanceOfSatisfying(AudioValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AudioValidationException.Reason.UNSUPPORTED_MEDIA));
    }

    private static AudioOperations operations(AudioProperties p) {
        return new AudioOperations(new AudioOperations.Policy(p.enabled(), p.mockMode(),
                new AudioOperations.Channel(p.asr().enabled(), p.asr().provider(), p.asr().model()),
                new AudioOperations.Channel(p.tts().enabled(), p.tts().provider(), p.tts().model()),
                p.limits().toAudioLimits()), MockAsrProvider::new, MockTtsProvider::new);
    }

    private static NodeContext context(UUID runId, NodeDef node, Map<String, Object> variables) {
        return new NodeContext(runId, UUID.randomUUID(), UUID.randomUUID(), node, variables,
                new InMemoryRunEventBus(EngineProperties.defaults()), new RunControl() {
                    @Override public boolean isCanceled() { return false; }
                });
    }

    private static NodeDef node(String id, String type, Map<String, Object> params) {
        return new NodeDef(id, type, id, params, Set.of(), null, null);
    }
}
