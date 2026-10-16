package com.aiflow.engine.node;

import com.aiflow.engine.graph.NodeDef;

import java.util.List;
import java.util.Set;

public interface NodeExecutor {

    String type();

    default Set<String> requiredCapabilities() {
        return Set.of();
    }

    /**
     * 装配期参数校验：注册工作流时就报出「这个节点少配了什么」，而不是等运行到一半才炸。
     *
     * <p>扩展点放在执行器上而不是往 {@code GraphValidator} 里塞 node-type 分支：节点的参数语义归它自己，
     * 校验器只负责把问题汇总出去（默认空实现，存量执行器不受影响）。
     */
    default void validate(NodeDef node, List<String> problems) {
    }

    NodeResult execute(NodeContext context) throws Exception;
}
