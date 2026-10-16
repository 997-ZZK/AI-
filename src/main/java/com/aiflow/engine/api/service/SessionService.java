package com.aiflow.engine.api.service;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.persistence.entity.SessionEntity;
import com.aiflow.engine.persistence.repo.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final UserService userService;
    private final WorkflowService workflowService;

    public SessionService(SessionRepository sessionRepository, UserService userService,
                          WorkflowService workflowService) {
        this.sessionRepository = sessionRepository;
        this.userService = userService;
        this.workflowService = workflowService;
    }

    /**
     * 建会话：workflowName 非空时把会话绑定到该工作流（之后该会话的消息都跑它），
     * 为空则保持 null，发消息时回退到 aiflow.engine.default-workflow。
     */
    public SessionEntity create(UUID userId, String title, String workflowName) {
        userService.get(userId);
        SessionEntity session = new SessionEntity(UUID.randomUUID(), userId, title, Instant.now());
        session.setWorkflowId(workflowService.resolveIdByName(workflowName));
        return sessionRepository.save(session);
    }

    public SessionEntity get(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
    }
}
