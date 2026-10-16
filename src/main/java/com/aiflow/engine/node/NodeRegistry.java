package com.aiflow.engine.node;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class NodeRegistry {

    private final Map<String, NodeExecutor> executors;

    public NodeRegistry(List<NodeExecutor> nodeExecutors) {
        Map<String, NodeExecutor> registered = new LinkedHashMap<>();
        for (NodeExecutor executor : nodeExecutors) {
            NodeExecutor previous = registered.put(executor.type(), executor);
            if (previous != null) {
                throw new IllegalStateException("节点类型重复注册: " + executor.type());
            }
        }
        this.executors = Map.copyOf(registered);
    }

    public Optional<NodeExecutor> find(String type) {
        return Optional.ofNullable(executors.get(type));
    }

    public NodeExecutor require(String type) {
        return find(type).orElseThrow(() -> new BizException(ErrorCode.INTERNAL_ERROR, "未注册的节点类型: " + type));
    }

    public Set<String> types() {
        return executors.keySet();
    }
}
