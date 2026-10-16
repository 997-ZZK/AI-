package com.aiflow.engine.api.service;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.graph.GraphValidator;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.persistence.entity.SessionEntity;
import com.aiflow.engine.persistence.entity.WorkflowEntity;
import com.aiflow.engine.persistence.repo.WorkflowRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final GraphValidator graphValidator;
    private final ObjectMapper objectMapper;
    private final EngineProperties properties;

    public WorkflowService(WorkflowRepository workflowRepository, GraphValidator graphValidator,
                           ObjectMapper objectMapper, EngineProperties properties) {
        this.workflowRepository = workflowRepository;
        this.graphValidator = graphValidator;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public WorkflowDefinition register(WorkflowDefinition definition) {
        graphValidator.validate(definition);
        WorkflowEntity entity = new WorkflowEntity(UUID.randomUUID(), definition.name(), definition.version(),
                objectMapper.valueToTree(definition), Instant.now());
        workflowRepository.save(entity);
        return definition;
    }

    public WorkflowDefinition loadById(UUID workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工作流不存在: " + workflowId));
        return parse(entity);
    }

    public WorkflowEntity loadForSession(SessionEntity session) {
        if (session.getWorkflowId() != null) {
            return workflowRepository.findById(session.getWorkflowId())
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工作流不存在: " + session.getWorkflowId()));
        }
        return workflowRepository.findFirstByNameOrderByVersionDesc(properties.defaultWorkflow())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "默认工作流未注册: " + properties.defaultWorkflow()));
    }

    /** 工作流名 -> id（会话级绑定用）；名字为空表示沿用默认工作流，留给发消息时兜底。 */
    public UUID resolveIdByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.strip();
        return workflowRepository.findFirstByNameOrderByVersionDesc(trimmed)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工作流不存在: " + trimmed))
                .getId();
    }

    public List<WorkflowSummary> list() {
        return workflowRepository.findAll().stream()
                .map(entity -> new WorkflowSummary(entity.getId(), entity.getName(), entity.getVersion(),
                        entity.getCreatedAt()))
                .toList();
    }

    private WorkflowDefinition parse(WorkflowEntity entity) {
        try {
            return objectMapper.treeToValue(entity.getDefinition(), WorkflowDefinition.class);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "工作流定义解析失败: " + entity.getId());
        }
    }

    public record WorkflowSummary(UUID id, String name, int version, Instant createdAt) {
    }
}
