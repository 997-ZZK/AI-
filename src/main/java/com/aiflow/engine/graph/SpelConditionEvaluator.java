package com.aiflow.engine.graph;

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SpelConditionEvaluator implements ConditionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    @Override
    public boolean evaluate(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        Expression parsed = cache.computeIfAbsent(expression, parser::parseExpression);
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariables(variables);
        return Boolean.TRUE.equals(parsed.getValue(context, Boolean.class));
    }
}
