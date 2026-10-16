package com.aiflow.engine.graph;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;

import java.util.List;

public class GraphValidationException extends BizException {

    private final List<String> problems;

    public GraphValidationException(List<String> problems) {
        super(ErrorCode.GRAPH_INVALID, "工作流定义校验失败: " + String.join("; ", problems), List.copyOf(problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> getProblems() {
        return problems;
    }
}
