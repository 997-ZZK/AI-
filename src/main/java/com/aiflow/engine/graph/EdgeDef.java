package com.aiflow.engine.graph;

public record EdgeDef(String from, String to, String when) {

    public EdgeDef {
        when = (when == null || when.isBlank()) ? null : when;
    }
}
