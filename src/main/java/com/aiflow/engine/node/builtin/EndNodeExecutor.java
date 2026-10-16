package com.aiflow.engine.node.builtin;

import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class EndNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "end";
    }

    @Override
    public NodeResult execute(NodeContext context) {
        Object outputs = context.param("outputs");
        if (outputs instanceof Map<?, ?> mapping) {
            Map<String, Object> result = new LinkedHashMap<>();
            mapping.forEach((outputKey, variableName) -> {
                Object value = context.variables().get(String.valueOf(variableName));
                if (value != null) {
                    result.put(String.valueOf(outputKey), value);
                }
            });
            context.setVariable("__output__", result);
            return NodeResult.success(Map.of("outputKeys", result.keySet()));
        }
        return NodeResult.success(Map.of());
    }
}
