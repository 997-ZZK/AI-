package com.aiflow.engine.graph;

import java.util.List;
import java.util.Optional;

public record WorkflowDefinition(
        String id,
        String name,
        int version,
        List<NodeDef> nodes,
        List<EdgeDef> edges
) {

    public WorkflowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public Optional<NodeDef> findNode(String nodeId) {
        return nodes.stream().filter(node -> nodeId.equals(node.id())).findFirst();
    }

    public NodeDef requireNode(String nodeId) {
        return findNode(nodeId).orElseThrow(() -> new IllegalStateException("节点不存在: " + nodeId));
    }
}
