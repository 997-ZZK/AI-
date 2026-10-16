package com.aiflow.engine.event;

import java.time.Instant;
import java.util.Map;

public record RunEvent(long seq, String runId, RunEventType type, String nodeId, Instant at,
                       Map<String, Object> data) {

    public static RunEvent of(String runId, RunEventType type, String nodeId, Map<String, Object> data) {
        return new RunEvent(0L, runId, type, nodeId, Instant.now(), data == null ? Map.of() : data);
    }

    public RunEvent withSeq(long newSeq) {
        return new RunEvent(newSeq, runId, type, nodeId, at, data);
    }
}
