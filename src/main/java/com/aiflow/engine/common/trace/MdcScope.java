package com.aiflow.engine.common.trace;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

public final class MdcScope implements AutoCloseable {

    private final Map<String, String> previous;

    private MdcScope(Map<String, String> previous) {
        this.previous = previous;
    }

    public static Map<String, String> capture() {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return context == null ? Map.of() : new HashMap<>(context);
    }

    public static MdcScope restore(Map<String, String> context) {
        Map<String, String> previous = capture();
        MDC.clear();
        if (context != null && !context.isEmpty()) {
            MDC.setContextMap(context);
        }
        return new MdcScope(previous);
    }

    @Override
    public void close() {
        MDC.clear();
        if (previous != null && !previous.isEmpty()) {
            MDC.setContextMap(previous);
        }
    }
}
