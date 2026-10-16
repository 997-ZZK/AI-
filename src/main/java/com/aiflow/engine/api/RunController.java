package com.aiflow.engine.api;

import com.aiflow.engine.api.dto.NodeRunDetailResponse;
import com.aiflow.engine.api.dto.NodeRunPageResponse;
import com.aiflow.engine.api.service.RunCoordinator;
import com.aiflow.engine.api.service.RunQueryService;
import com.aiflow.engine.api.service.PersistentDispatchWorker;
import com.aiflow.engine.api.stream.RunStreamService;
import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.auth.IdentityContext;
import com.aiflow.engine.common.auth.RequireIdentity;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.run.RunCancellationRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunQueryService runQueryService;
    private final RunStreamService runStreamService;
    private final RunCancellationRegistry cancellationRegistry;
    private final RunCoordinator runCoordinator;
    private final ApprovalController approvalController;
    private final PersistentDispatchWorker dispatchWorker;

    public RunController(RunQueryService runQueryService, RunStreamService runStreamService,
                         RunCancellationRegistry cancellationRegistry, RunCoordinator runCoordinator,
                         ApprovalController approvalController, PersistentDispatchWorker dispatchWorker) {
        this.runQueryService = runQueryService;
        this.runStreamService = runStreamService;
        this.cancellationRegistry = cancellationRegistry;
        this.runCoordinator = runCoordinator;
        this.approvalController = approvalController;
        this.dispatchWorker = dispatchWorker;
    }

    @GetMapping("/events/status")
    public ApiResponse<RunStreamService.Status> eventsStatus() {
        return ApiResponse.ok(runStreamService.status());
    }

    @GetMapping("/dispatch/status")
    public ApiResponse<PersistentDispatchWorker.Status> dispatchStatus() {
        return ApiResponse.ok(dispatchWorker.status());
    }

    @GetMapping("/{runId}")
    public ApiResponse<RunStatusResponse> get(@PathVariable UUID runId) {
        WorkflowRunEntity run = runQueryService.get(runId);
        return ApiResponse.ok(new RunStatusResponse(run.getId(), run.getStatus().name(), run.getOutput(),
                run.getError(), run.getSteps(), run.getStartedAt(), run.getFinishedAt()));
    }

    @GetMapping("/{runId}/nodes")
    public ApiResponse<NodeRunPageResponse> nodes(@PathVariable UUID runId,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(runQueryService.nodes(runId, page, size));
    }

    @GetMapping("/{runId}/nodes/{nodeRunId}")
    public ApiResponse<NodeRunDetailResponse> node(@PathVariable UUID runId, @PathVariable UUID nodeRunId) {
        return ApiResponse.ok(runQueryService.node(runId, nodeRunId));
    }

    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (!runQueryService.exists(runId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行不存在: " + runId);
        }
        return runStreamService.subscribe(runId.toString(), parseSeq(lastEventId));
    }

    /**
     * 取消有两条路：**运行中**的走进程内取消表（打断正在执行的节点）；**挂起中**（等人工审批）的
     * 没有线程在跑，走状态条件更新 + 待办收口。两条路的赢家都唯一，与「审批通过」也不会双双生效。
     */
    @PostMapping("/{runId}/cancel")
    @RequireIdentity
    public ApiResponse<CancelResponse> cancel(@PathVariable UUID runId,
                                              @RequestParam(required = false) String actor) {
        // 「谁取消的」和「谁批的」同一条规则：有身份以身份为准，自报不一致 = 400
        String who = IdentityContext.resolveActor(actor, "actor");
        if (dispatchWorker.cancel(runId, who)) {
            return ApiResponse.ok(new CancelResponse(runId, "CANCEL_REQUESTED"));
        }
        if (cancellationRegistry.cancel(runId)) {
            return ApiResponse.ok(new CancelResponse(runId, "CANCEL_REQUESTED"));
        }
        if (runCoordinator.cancelWaiting(runId, who)) {
            return ApiResponse.ok(new CancelResponse(runId, "CANCELED"));
        }
        throw new BizException(ErrorCode.CONFLICT, "任务未在本节点运行、也不在等待状态，无法取消: " + runId);
    }

    /** 运行视角的审批链（含历史层级：升级过的每一层都在）。 */
    @GetMapping("/{runId}/approvals")
    public ApiResponse<List<ApprovalController.ApprovalView>> approvals(@PathVariable UUID runId) {
        if (!runQueryService.exists(runId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行不存在: " + runId);
        }
        return ApiResponse.ok(approvalController.listByRun(runId));
    }

    private long parseSeq(String lastEventId) {
        if (lastEventId == null) {
            return 0L;
        }
        try {
            String value = lastEventId.trim();
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Last-Event-ID 必须为非负十进制整数");
        }
    }

    public record RunStatusResponse(UUID runId, String status, JsonNode output, String error, int steps,
                                    Instant startedAt, Instant finishedAt) {
    }

    public record CancelResponse(UUID runId, String status) {
    }
}
