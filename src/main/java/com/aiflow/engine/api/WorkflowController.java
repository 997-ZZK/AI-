package com.aiflow.engine.api;

import com.aiflow.engine.api.service.WorkflowService;
import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.graph.WorkflowDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping
    public ApiResponse<WorkflowDefinition> register(@RequestBody WorkflowDefinition definition) {
        return ApiResponse.ok(workflowService.register(definition));
    }

    @GetMapping("/{workflowId}")
    public ApiResponse<WorkflowDefinition> get(@PathVariable UUID workflowId) {
        return ApiResponse.ok(workflowService.loadById(workflowId));
    }

    @GetMapping
    public ApiResponse<List<WorkflowService.WorkflowSummary>> list() {
        return ApiResponse.ok(workflowService.list());
    }
}
