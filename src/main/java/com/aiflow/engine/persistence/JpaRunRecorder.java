package com.aiflow.engine.persistence;

import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.persistence.entity.NodeRunEntity;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.NodeRunRepository;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.run.RunRecorder;
import com.aiflow.engine.run.RunExecutionRegistry;
import com.aiflow.engine.run.RunRequest;
import com.aiflow.engine.run.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class JpaRunRecorder implements RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(JpaRunRecorder.class);

    private final WorkflowRunRepository runRepository;
    private final NodeRunRepository nodeRunRepository;
    private final ObjectMapper objectMapper;
    private final RunExecutionRegistry executions;
    private final MessageRepository messageRepository;

    public JpaRunRecorder(WorkflowRunRepository runRepository, NodeRunRepository nodeRunRepository,
                          ObjectMapper objectMapper) {
        this(runRepository, nodeRunRepository, objectMapper, new RunExecutionRegistry(), null);
    }

    public JpaRunRecorder(WorkflowRunRepository runRepository, NodeRunRepository nodeRunRepository,
                          ObjectMapper objectMapper, RunExecutionRegistry executions) {
        this(runRepository, nodeRunRepository, objectMapper, executions, null);
    }

    @Autowired
    public JpaRunRecorder(WorkflowRunRepository runRepository, NodeRunRepository nodeRunRepository,
                          ObjectMapper objectMapper, RunExecutionRegistry executions,
                          MessageRepository messageRepository) {
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.objectMapper = objectMapper;
        this.executions = executions;
        this.messageRepository = messageRepository;
    }

    @Override
    public UUID startRun(RunRequest request, WorkflowDefinition definition) {
        return executions.write(request.runId(), () -> startRunOwned(request, definition));
    }

    private UUID startRunOwned(RunRequest request, WorkflowDefinition definition) {
        Instant now = Instant.now();
        WorkflowRunEntity entity = runRepository.findById(request.runId()).orElseGet(WorkflowRunEntity::new);
        if (entity.getId() == null) {
            entity.setId(request.runId());
            entity.setCreatedAt(now);
            entity.setInput(toJson(request.input()));
            entity.setSteps(0);
        }
        entity.setWorkflowId(request.workflowId());
        entity.setWorkflowVersion(definition.version());
        entity.setSessionId(request.sessionId());
        entity.setUserId(request.userId());
        entity.setStatus(RunStatus.RUNNING);
        entity.setStartedAt(now);
        runRepository.save(entity);
        return entity.getId();
    }

    @Override
    @Transactional
    public void finishRun(UUID runId, RunStatus status, Map<String, Object> output, String error, int steps) {
        executions.write(runId, () -> runRepository.findById(runId).ifPresentOrElse(entity -> {
            entity.setStatus(status);
            entity.setOutput(toJson(output));
            entity.setError(error);
            entity.setSteps(steps);
            entity.setFinishedAt(Instant.now());
            // 收尾时清掉「在等谁」：终态的运行不该再挂着等待标记（否则查询/看板会误读）
            entity.setWaitingNodeId(null);
            runRepository.save(entity);
            saveReply(entity, status, output);
        }, () -> log.warn("finishRun: run {} not found", runId)));
    }

    @Override
    public void markWaiting(UUID runId, String nodeId) {
        executions.write(runId, () -> runRepository.findById(runId).ifPresentOrElse(entity -> {
            entity.setStatus(RunStatus.WAITING_APPROVAL);
            entity.setWaitingNodeId(nodeId);
            runRepository.save(entity);
        }, () -> log.warn("markWaiting: run {} not found", runId)));
    }

    @Override
    public boolean claimResume(UUID runId) {
        return executions.write(runId,
                () -> runRepository.transitionStatus(runId, RunStatus.WAITING_APPROVAL, RunStatus.RUNNING) == 1);
    }

    @Override
    public void suspendNodeRun(UUID nodeRunId, Map<String, Object> output) {
        nodeRunRepository.findById(nodeRunId).ifPresentOrElse(found -> executions.write(found.getRunId(), () -> {
            NodeRunEntity entity = nodeRunRepository.findById(nodeRunId).orElseThrow();
            entity.setStatus(NodeRunStatus.WAITING);
            entity.setOutput(toJson(output));
            // ended_at 留空：挂起不是收口，恢复时才写
            nodeRunRepository.save(entity);
        }), () -> log.warn("suspendNodeRun: node run {} not found", nodeRunId));
    }

    @Override
    public UUID startNodeRun(UUID runId, NodeDef node, int attempt, Map<String, Object> inputSnapshot) {
        return executions.write(runId, () -> startNodeRunOwned(runId, node, attempt, inputSnapshot));
    }

    private UUID startNodeRunOwned(UUID runId, NodeDef node, int attempt, Map<String, Object> inputSnapshot) {
        NodeRunEntity entity = new NodeRunEntity();
        entity.setId(UUID.randomUUID());
        entity.setRunId(runId);
        entity.setNodeId(node.id());
        entity.setNodeType(node.type());
        entity.setStatus(NodeRunStatus.RUNNING);
        entity.setAttempt(attempt);
        entity.setInput(toJson(inputSnapshot));
        entity.setStartedAt(Instant.now());
        nodeRunRepository.save(entity);
        return entity.getId();
    }

    @Override
    public void finishNodeRun(UUID nodeRunId, NodeRunStatus status, Map<String, Object> output, String error,
                              Instant endedAt) {
        nodeRunRepository.findById(nodeRunId).ifPresentOrElse(found -> executions.write(found.getRunId(), () -> {
            NodeRunEntity entity = nodeRunRepository.findById(nodeRunId).orElseThrow();
            entity.setStatus(status);
            entity.setOutput(toJson(output));
            entity.setError(error);
            entity.setEndedAt(endedAt);
            nodeRunRepository.save(entity);
        }), () -> log.warn("finishNodeRun: node run {} not found", nodeRunId));
    }

    private JsonNode toJson(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    private void saveReply(WorkflowRunEntity run, RunStatus status, Map<String, Object> output) {
        if (messageRepository == null || run.getSessionId() == null || run.getUserId() == null
                || status == RunStatus.CANCELED
                || messageRepository.findFirstByRunIdAndRole(run.getId(), "ASSISTANT").isPresent()) {
            return;
        }
        Object reply = output.get("reply");
        if (reply == null) {
            reply = output.get("fallback");
        }
        if (reply != null) {
            messageRepository.save(new MessageEntity(UUID.randomUUID(), run.getSessionId(), run.getUserId(),
                    "ASSISTANT", "TEXT", String.valueOf(reply), null, run.getId(), Instant.now()));
        }
    }
}
