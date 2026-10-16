package com.aiflow.engine.run;

import java.util.Map;
import java.util.UUID;

public record RunResult(UUID runId, RunStatus status, Map<String, Object> output, String error, int steps) {
}
