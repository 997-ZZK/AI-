package com.aiflow.engine.node.builtin;

import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeResult;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StartNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "start";
    }

    @Override
    public NodeResult execute(NodeContext context) {
        return NodeResult.success(Map.of());
    }
}
