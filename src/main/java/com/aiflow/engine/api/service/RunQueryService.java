package com.aiflow.engine.api.service;

import com.aiflow.engine.api.dto.NodeRunDetailResponse;
import com.aiflow.engine.api.dto.NodeRunPageResponse;
import com.aiflow.engine.api.dto.NodeRunSummaryResponse;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.persistence.repo.NodeRunRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class RunQueryService {

    private final WorkflowRunRepository runRepository;
    private final NodeRunRepository nodeRunRepository;

    public RunQueryService(WorkflowRunRepository runRepository, NodeRunRepository nodeRunRepository) {
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
    }

    public WorkflowRunEntity get(UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "运行不存在: " + runId));
    }

    public boolean exists(UUID runId) {
        return runRepository.existsById(runId);
    }

    public NodeRunPageResponse nodes(UUID runId, int page, int size) {
        if (page < 0 || size < 1 || size > 200 || (long) page * size > Integer.MAX_VALUE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分页参数要求 page>=0、1<=size<=200，且偏移不超过整数上限");
        }
        if (!exists(runId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行不存在: " + runId);
        }
        var slice = nodeRunRepository.findReplaySummariesByRunId(runId, PageRequest.of(page, size));
        var items = slice.getContent().stream().map(node -> new NodeRunSummaryResponse(
                node.getNodeRunId(), node.getNodeId(), node.getNodeType(), node.getAttempt(),
                node.getStatus().name(), node.getStartedAt(), node.getEndedAt(),
                elapsedMs(node.getStartedAt(), node.getEndedAt()))).toList();
        return new NodeRunPageResponse(items, page, size, slice.hasNext());
    }

    public NodeRunDetailResponse node(UUID runId, UUID nodeRunId) {
        var node = nodeRunRepository.findByIdAndRunId(nodeRunId, runId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "节点执行记录不存在"));
        return new NodeRunDetailResponse(node.getId(), node.getNodeId(), node.getNodeType(), node.getAttempt(),
                node.getStatus().name(), node.getStartedAt(), node.getEndedAt(),
                elapsedMs(node.getStartedAt(), node.getEndedAt()), node.getInput(), node.getOutput(), node.getError());
    }

    private static Long elapsedMs(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null || endedAt.isBefore(startedAt)) {
            return null;
        }
        // Total persisted wall time, including approval waits; never an estimate for unfinished nodes.
        return Duration.between(startedAt, endedAt).toMillis();
    }
}
