package com.aiflow.engine.node;

import com.aiflow.engine.common.trace.MdcScope;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.graph.NodeDef;

import java.util.Map;
import java.util.UUID;

public class NodeContext {

    private final UUID runId;
    private final UUID sessionId;
    private final UUID userId;
    private final NodeDef node;
    private final Map<String, Object> variables;
    private final RunEventBus eventBus;
    private final RunControl runControl;
    private final Map<String, String> mdc;

    public NodeContext(UUID runId, UUID sessionId, UUID userId, NodeDef node, Map<String, Object> variables,
                       RunEventBus eventBus, RunControl runControl) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.node = node;
        this.variables = variables;
        this.eventBus = eventBus;
        this.runControl = runControl;
        this.mdc = MdcScope.capture();
    }

    public UUID runId() {
        return runId;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID userId() {
        return userId;
    }

    public NodeDef node() {
        return node;
    }

    public Map<String, String> mdc() {
        return mdc;
    }

    public Map<String, Object> variables() {
        return variables;
    }

    public Object param(String key) {
        return node.params().get(key);
    }

    public String paramString(String key, String defaultValue) {
        Object value = param(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public long paramLong(String key, long defaultValue) {
        Object value = param(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public void setVariable(String key, Object value) {
        runControl.check();
        variables.put(key, value);
    }

    public void emitDelta(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        runControl.check();
        eventBus.publishSafely(RunEvent.of(runId.toString(), RunEventType.DELTA, node.id(), Map.of("text", text)));
    }

    public boolean isCanceled() {
        return runControl.isCanceled();
    }

    public void throwIfCanceled() {
        runControl.throwIfCanceled();
    }
}
