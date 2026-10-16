package com.aiflow.engine.graph;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpelConditionEvaluatorTest {

    private final SpelConditionEvaluator evaluator = new SpelConditionEvaluator();

    @Test
    void blankExpressionIsAlwaysTrue() {
        assertThat(evaluator.evaluate(null, Map.of())).isTrue();
        assertThat(evaluator.evaluate("  ", Map.of())).isTrue();
    }

    @Test
    void evaluatesVariables() {
        Map<String, Object> variables = Map.of("score", 0.8, "intent", "weather");

        assertThat(evaluator.evaluate("#score > 0.5", variables)).isTrue();
        assertThat(evaluator.evaluate("#score > 0.9", variables)).isFalse();
        assertThat(evaluator.evaluate("#intent == 'weather'", variables)).isTrue();
    }
}
