package com.aiflow.engine.node.builtin;

import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.RunCanceledException;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DelayNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "delay";
    }

    @Override
    public NodeResult execute(NodeContext context) {
        long millis = context.paramLong("millis", 50L);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        }
        context.throwIfCanceled();
        return NodeResult.success(Map.of("delayedMs", millis));
    }
}
