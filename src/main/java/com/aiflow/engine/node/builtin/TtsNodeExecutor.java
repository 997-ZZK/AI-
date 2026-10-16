package com.aiflow.engine.node.builtin;

import com.aiflow.engine.audio.AudioHandleStore;
import com.aiflow.engine.audio.AudioInput;
import com.aiflow.engine.audio.AudioOperations;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.TemplateRenderer;
import com.aiflow.engine.node.RunCanceledException;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.run.RunLeaseLostException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Synchronous workflow TTS. Bytes are kept in the run-local handle store. */
@Component
public final class TtsNodeExecutor implements NodeExecutor {

    private static final Set<String> ALLOWED = Set.of("text", "voice", "format", "fallback");

    private final AudioOperations audio;
    private final AudioHandleStore handles;

    public TtsNodeExecutor(AudioOperations audio, AudioHandleStore handles) {
        this.audio = audio;
        this.handles = handles;
    }

    @Override
    public String type() {
        return "tts.speak";
    }

    @Override
    public Set<String> requiredCapabilities() {
        return Set.of(Capabilities.TTS);
    }

    @Override
    public void validate(NodeDef node, List<String> problems) {
        validateParams(node, problems, ALLOWED);
    }

    @Override
    public NodeResult execute(NodeContext context) {
        validateRuntimeParams(context);
        context.throwIfCanceled();
        AudioHandleStore.Access access = handles.access(context.runId(), context::throwIfCanceled);
        String text = TemplateRenderer.render(stringParam(context, "text", "${input}"), context.variables());
        String voice = stringParam(context, "voice", null);
        String format = stringParam(context, "format", "wav");
        context.throwIfCanceled();
        TtsResponse response = audio.synthesize(text, voice, format, TtsNodeExecutor::isControlFailure);
        context.throwIfCanceled();
        String handle = handles.put(access, new AudioInput(response.audioBytes(), "audio/wav"),
                context::throwIfCanceled);
        try {
            context.throwIfCanceled();
            context.setVariable("audioHandle", handle);
        } catch (RuntimeException | Error failure) {
            // A cancellation/lease failure can happen after put(). Do not let a
            // failed attempt retain a handle until the whole run finally closes.
            handles.remove(access, handle);
            throw failure;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("audioHandle", handle);
        output.put("provider", response.provider());
        output.put("model", response.model());
        output.put("durationMs", response.durationMs());
        output.put("byteLength", response.audioBytes().length);
        return NodeResult.success(output);
    }

    static void validateParams(NodeDef node, List<String> problems, Set<String> allowed) {
        node.params().forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!allowed.contains(name)) {
                problems.add("节点 " + node.id() + " 的 " + node.type() + " 参数不支持: " + name);
            } else if (!(value instanceof String)) {
                problems.add("节点 " + node.id() + " 的 " + name + " 必须是字符串");
            } else if ("format".equals(name) && !isWavFormat((String) value)) {
                problems.add("节点 " + node.id() + " 的 format 只支持 wav");
            }
        });
    }

    private static void validateRuntimeParams(NodeContext context) {
        context.node().params().forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!ALLOWED.contains(name)) {
                throw new IllegalArgumentException("音频节点参数不支持");
            }
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("音频节点参数必须是字符串");
            }
            if ("format".equals(name) && !isWavFormat((String) value)) {
                throw new AudioValidationException(AudioValidationException.Reason.UNSUPPORTED_MEDIA);
            }
        });
    }

    private static boolean isWavFormat(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "wav".equals(normalized) || "audio/wav".equals(normalized);
    }

    private static String stringParam(NodeContext context, String key, String fallback) {
        Object value = context.param(key);
        return value == null ? fallback : (String) value;
    }

    private static boolean isControlFailure(RuntimeException failure) {
        return failure instanceof RunCanceledException || failure instanceof RunLeaseLostException;
    }
}
