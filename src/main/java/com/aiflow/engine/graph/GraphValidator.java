package com.aiflow.engine.graph;

import com.aiflow.engine.node.NodeRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class GraphValidator {

    private final NodeRegistry nodeRegistry;

    public GraphValidator(NodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    public ValidatedGraph validate(WorkflowDefinition definition) {
        List<String> problems = new ArrayList<>();
        if (definition.name() == null || definition.name().isBlank()) {
            problems.add("workflow.name 不能为空");
        }
        if (definition.version() <= 0) {
            problems.add("workflow.version 必须为正整数");
        }
        if (definition.nodes().isEmpty()) {
            problems.add("workflow.nodes 不能为空");
        }
        Map<String, NodeDef> nodesById = new LinkedHashMap<>();
        for (NodeDef node : definition.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                problems.add("存在 id 为空的节点");
                continue;
            }
            if (nodesById.putIfAbsent(node.id(), node) != null) {
                problems.add("节点 id 重复: " + node.id());
            }
            if (node.type() == null || node.type().isBlank()) {
                problems.add("节点 " + node.id() + " 缺少 type");
            } else if (nodeRegistry.find(node.type()).isEmpty()) {
                problems.add("节点 " + node.id() + " 使用了未注册的类型: " + node.type());
            } else {
                // 类型已注册：把参数校验交给执行器自己（例如审批节点必须配审批人）
                nodeRegistry.require(node.type()).validate(node, problems);
            }
        }
        Map<String, List<EdgeDef>> outgoing = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String nodeId : nodesById.keySet()) {
            outgoing.put(nodeId, new ArrayList<>());
            inDegree.put(nodeId, 0);
        }
        for (EdgeDef edge : definition.edges()) {
            if (edge.from() == null || edge.to() == null) {
                problems.add("存在 from/to 为空的边");
                continue;
            }
            boolean fromExists = nodesById.containsKey(edge.from());
            boolean toExists = nodesById.containsKey(edge.to());
            if (!fromExists) {
                problems.add("边的起点不存在: " + edge.from());
            }
            if (!toExists) {
                problems.add("边的终点不存在: " + edge.to());
            }
            if (edge.from().equals(edge.to())) {
                problems.add("不允许自环: " + edge.from());
            }
            if (fromExists && toExists) {
                outgoing.get(edge.from()).add(edge);
                inDegree.merge(edge.to(), 1, Integer::sum);
            }
        }
        if (!problems.isEmpty()) {
            throw new GraphValidationException(problems);
        }
        Set<String> entryNodes = new LinkedHashSet<>();
        inDegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                entryNodes.add(nodeId);
            }
        });
        List<String> order = topologicalOrder(outgoing, inDegree, entryNodes);
        if (order.size() != nodesById.size()) {
            Set<String> sorted = new LinkedHashSet<>(order);
            List<String> cyclic = nodesById.keySet().stream().filter(nodeId -> !sorted.contains(nodeId)).toList();
            throw new GraphValidationException(List.of("检测到环，涉及节点: " + cyclic));
        }
        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>(entryNodes);
        while (!stack.isEmpty()) {
            String nodeId = stack.pop();
            if (!reachable.add(nodeId)) {
                continue;
            }
            for (EdgeDef edge : outgoing.get(nodeId)) {
                stack.push(edge.to());
            }
        }
        List<String> unreachable = nodesById.keySet().stream().filter(nodeId -> !reachable.contains(nodeId)).toList();
        if (!unreachable.isEmpty()) {
            throw new GraphValidationException(List.of("存在不可达节点: " + unreachable));
        }
        return new ValidatedGraph(List.copyOf(order), Set.copyOf(entryNodes), Map.copyOf(outgoing));
    }

    private List<String> topologicalOrder(Map<String, List<EdgeDef>> outgoing, Map<String, Integer> inDegree,
                                          Set<String> entryNodes) {
        Map<String, Integer> remaining = new LinkedHashMap<>(inDegree);
        ArrayDeque<String> queue = new ArrayDeque<>(entryNodes);
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            order.add(nodeId);
            for (EdgeDef edge : outgoing.get(nodeId)) {
                int left = remaining.merge(edge.to(), -1, Integer::sum);
                if (left == 0) {
                    queue.add(edge.to());
                }
            }
        }
        return order;
    }

    public record ValidatedGraph(List<String> topologicalOrder, Set<String> entryNodes,
                                 Map<String, List<EdgeDef>> outgoing) {
    }
}
