package com.aiflow.engine.graph;

import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeRegistry;
import com.aiflow.engine.node.NodeResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphValidatorTest {

    private final NodeRegistry registry = new NodeRegistry(List.of(
            fixedExecutor("start"), fixedExecutor("llm.chat"), fixedExecutor("end")));
    private final GraphValidator validator = new GraphValidator(registry);

    @Test
    void acceptsValidDag() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", "demo", 1,
                List.of(node("start", "start"), node("llm", "llm.chat"), node("end", "end")),
                List.of(edge("start", "llm"), edge("llm", "end")));

        GraphValidator.ValidatedGraph graph = validator.validate(definition);

        assertThat(graph.topologicalOrder()).containsExactly("start", "llm", "end");
        assertThat(graph.entryNodes()).containsExactly("start");
    }

    @Test
    void rejectsCycle() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", "cycle", 1,
                List.of(node("a", "llm.chat"), node("b", "llm.chat")),
                List.of(edge("a", "b"), edge("b", "a")));

        assertThatThrownBy(() -> validator.validate(definition))
                .isInstanceOf(GraphValidationException.class)
                .hasMessageContaining("环");
    }

    @Test
    void rejectsUnknownNodeType() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", "unknown", 1,
                List.of(node("a", "nope")),
                List.of());

        assertThatThrownBy(() -> validator.validate(definition))
                .isInstanceOf(GraphValidationException.class)
                .hasMessageContaining("未注册的类型");
    }

    @Test
    void rejectsDuplicateNodeId() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", "duplicate", 1,
                List.of(node("a", "start"), node("a", "end")),
                List.of());

        assertThatThrownBy(() -> validator.validate(definition))
                .isInstanceOf(GraphValidationException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void rejectsSelfLoop() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", "self-loop", 1,
                List.of(node("a", "llm.chat")),
                List.of(edge("a", "a")));

        assertThatThrownBy(() -> validator.validate(definition))
                .isInstanceOf(GraphValidationException.class)
                .hasMessageContaining("自环");
    }

    @Test
    void rejectsDanglingEdge() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", "dangling", 1,
                List.of(node("a", "start")),
                List.of(edge("a", "ghost")));

        assertThatThrownBy(() -> validator.validate(definition))
                .isInstanceOf(GraphValidationException.class)
                .hasMessageContaining("终点不存在");
    }

    private NodeDef node(String id, String type) {
        return new NodeDef(id, type, id, Map.of(), Set.of(), null, null);
    }

    private EdgeDef edge(String from, String to) {
        return new EdgeDef(from, to, null);
    }

    private static NodeExecutor fixedExecutor(String type) {
        return new NodeExecutor() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public NodeResult execute(NodeContext context) {
                return NodeResult.success(Map.of());
            }
        };
    }
}
