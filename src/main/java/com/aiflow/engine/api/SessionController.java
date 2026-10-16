package com.aiflow.engine.api;

import com.aiflow.engine.api.service.SessionMessageService;
import com.aiflow.engine.api.service.SessionService;
import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.persistence.entity.SessionEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionMessageService sessionMessageService;

    public SessionController(SessionService sessionService, SessionMessageService sessionMessageService) {
        this.sessionService = sessionService;
        this.sessionMessageService = sessionMessageService;
    }

    @PostMapping
    public ApiResponse<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
        SessionEntity session = sessionService.create(request.userId(), request.title(), request.workflowName());
        return ApiResponse.ok(toResponse(session));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<SessionResponse> get(@PathVariable UUID sessionId) {
        return ApiResponse.ok(toResponse(sessionService.get(sessionId)));
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<MessageAcceptedResponse> submitMessage(@PathVariable UUID sessionId,
                                                              @Valid @RequestBody PostMessageRequest request) {
        SessionMessageService.Accepted accepted = sessionMessageService.submit(sessionId, request.clientMsgId(),
                request.content(), request.attachmentIds());
        return ApiResponse.ok(new MessageAcceptedResponse(accepted.messageId(), accepted.runId(), accepted.status()));
    }

    private SessionResponse toResponse(SessionEntity session) {
        return new SessionResponse(session.getId(), session.getUserId(), session.getWorkflowId(), session.getTitle(),
                session.getStatus(), session.getLastActiveAt(), session.getCreatedAt());
    }

    public record CreateSessionRequest(@NotNull UUID userId, String title, String workflowName) {
    }

    public record SessionResponse(UUID id, UUID userId, UUID workflowId, String title, String status,
                                  Instant lastActiveAt, Instant createdAt) {
    }

    /** attachmentIds 是先前 POST /api/v1/attachments 返回的附件 id，多模态节点从运行变量 attachments 里取。 */
    public record PostMessageRequest(@NotBlank String clientMsgId, @NotBlank String content,
                                     List<UUID> attachmentIds) {
    }

    public record MessageAcceptedResponse(UUID messageId, UUID runId, String status) {
    }
}
