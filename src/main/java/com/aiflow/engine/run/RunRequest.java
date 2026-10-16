package com.aiflow.engine.run;

import java.util.Map;
import java.util.UUID;

public record RunRequest(UUID runId, UUID userId, UUID sessionId, UUID workflowId, Map<String, Object> input) {

    public RunRequest {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
