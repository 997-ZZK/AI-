package com.aiflow.engine.node.builtin;

import com.aiflow.engine.audio.AsrResponse;
import com.aiflow.engine.audio.AudioHandleStore;
import com.aiflow.engine.audio.AudioOperations;
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
import java.util.Map;
import java.util.Set;

/** Synchronous workflow ASR. Only the transcript enters workflow variables. */
@Component
public final class AsrNodeExecutor implements NodeExecutor {

    private static final Set<String> ALLOWED = Set.of("audioHandle", "fallback");

    private final AudioOperations audio;
    private final AudioHandleStore handles;

    public AsrNodeExecutor(AudioOperations audio, AudioHandleStore handles) {
        this.audio = audio;
        this.handles = handles;
    }

    @Override
    public String type() {
        return "asr.transcribe";
    }

    @Override
    public Set<String> requiredCapabilities() {
        return Set.of(Capabilities.ASR);
    }

    @Override
    public void validate(NodeDef node, List<String> problems) {
        TtsNodeExecutor.validateParams(node, problems, ALLOWED);
    }

    @Override
    public NodeResult execute(NodeContext context) {
        validateRuntimeParams(context);
        context.throwIfCanceled();
        AudioHandleStore.Access access = handles.access(context.runId(), context::throwIfCanceled);
        String handleTemplate = stringParam(context, "audioHandle", "${audioHandle}");
        String handle = TemplateRenderer.render(handleTemplate, context.variables()).strip();
        context.throwIfCanceled();
        AsrResponse response = audio.transcribe(() -> handles.get(access, handle, context::throwIfCanceled),
                AsrNodeExecutor::isControlFailure);
        context.throwIfCanceled();
        context.setVariable("transcript", response.text());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("transcript", response.text());
        output.put("provider", response.provider());
        output.put("model", response.model());
        output.put("durationMs", response.durationMs());
        return NodeResult.success(output);
    }

    private static void validateRuntimeParams(NodeContext context) {
        context.node().params().forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!ALLOWED.contains(name) || !(value instanceof String)) {
                throw new IllegalArgumentException("音频节点参数无效");
            }
        });
    }

    private static String stringParam(NodeContext context, String key, String fallback) {
        Object value = context.param(key);
        return value == null ? fallback : (String) value;
    }

    private static boolean isControlFailure(RuntimeException failure) {
        return failure instanceof RunCanceledException || failure instanceof RunLeaseLostException;
    }
}
