package com.aiflow.engine.graph;

import java.util.Map;

public interface ConditionEvaluator {

    boolean evaluate(String expression, Map<String, Object> variables);
}
