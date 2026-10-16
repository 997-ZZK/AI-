package com.aiflow.engine.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record NodeDef(
        String id,
        String type,
        String name,
        Map<String, Object> params,
        Set<String> requires,
        RetryPolicy retry,
        Integer timeoutSeconds
) {

    public NodeDef {
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        requires = requires == null ? Set.of() : Set.copyOf(requires);
    }

    public String fallback() {
        Object value = params.get("fallback");
        return value == null ? null : String.valueOf(value);
    }
}
