package com.aiflow.engine.api.service;

import com.aiflow.engine.attachment.AttachmentStore;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.entity.SessionEntity;
import com.aiflow.engine.persistence.entity.WorkflowEntity;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.SessionRepository;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.safety.ContentSafety;
import com.aiflow.engine.run.RunStatus;
import com.aiflow.engine.session.SessionDispatcher;
import com.aiflow.engine.session.DispatchTaskStore;
import com.aiflow.engine.config.DispatchProperties;
import com.aiflow.engine.config.EngineProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class SessionMessageService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowService workflowService;
    private final SessionDispatcher sessionDispatcher;
    private final RunCoordinator runCoordinator;
    private final AttachmentStore attachmentStore;
    private final ContentSafety contentSafety;
    private final ObjectMapper objectMapper;
    private final DispatchTaskStore dispatchTasks;
    private final DispatchProperties dispatchProperties;
    private final EngineProperties engineProperties;

    public SessionMessageService(SessionRepository sessionRepository, MessageRepository messageRepository,
                                 WorkflowRunRepository workflowRunRepository, WorkflowService workflowService,
                                 SessionDispatcher sessionDispatcher, RunCoordinator runCoordinator,
                                 AttachmentStore attachmentStore, ContentSafety contentSafety,
                                 ObjectMapper objectMapper, DispatchTaskStore dispatchTasks,
                                 DispatchProperties dispatchProperties, EngineProperties engineProperties) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowService = workflowService;
        this.sessionDispatcher = sessionDispatcher;
        this.runCoordinator = runCoordinator;
        this.attachmentStore = attachmentStore;
        this.contentSafety = contentSafety;
        this.objectMapper = objectMapper;
        this.dispatchTasks = dispatchTasks;
        this.dispatchProperties = dispatchProperties;
        this.engineProperties = engineProperties;
    }

    @Transactional
    public Accepted submit(UUID sessionId, String clientMsgId, String content, List<UUID> attachmentIds) {
        // 输入审核放在最前面：不花任何 I/O，却能把违规内容挡在入库与排队之前。
        ContentSafety.Verdict verdict = contentSafety.review(content, ContentSafety.Direction.INPUT);
        if (!verdict.allowed()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "内容审核未通过（" + verdict.category() + "）：" + verdict.reason());
        }
        // 附件在这里就要校验存在性：让「引用了一个不存在的图片」在提交消息时失败，
        // 而不是等到节点执行到一半、用户只看到一句「运行失败」。
        if (attachmentIds != null) {
            attachmentIds.forEach(attachmentStore::require);
        }
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        if (dispatchProperties.enabled()) {
            dispatchTasks.lockSession(sessionId);
        }
        Optional<MessageEntity> existing = messageRepository.findFirstBySessionIdAndClientMsgId(sessionId, clientMsgId);
        if (existing.isPresent()) {
            return acceptedExisting(existing.get());
        }
        if (dispatchProperties.enabled()
                && dispatchTasks.countOutstanding(sessionId) >= engineProperties.sessionQueueCapacity()) {
            throw new BizException(ErrorCode.RATE_LIMITED, "会话排队已满，请稍后重试");
        }
        WorkflowEntity workflow = workflowService.loadForSession(session);
        Instant now = Instant.now();
        MessageEntity message = new MessageEntity(UUID.randomUUID(), sessionId, session.getUserId(), "USER", "TEXT",
                content, clientMsgId, null, now);
        try {
            messageRepository.saveAndFlush(message);
        } catch (DataIntegrityViolationException e) {
            MessageEntity raced = messageRepository.findFirstBySessionIdAndClientMsgId(sessionId, clientMsgId)
                    .orElseThrow(() -> new BizException(ErrorCode.CONFLICT, "消息重复提交"));
            return acceptedExisting(raced);
        }
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(UUID.randomUUID());
        run.setWorkflowId(workflow.getId());
        run.setWorkflowVersion(workflow.getVersion());
        run.setSessionId(sessionId);
        run.setUserId(session.getUserId());
        run.setStatus(RunStatus.PENDING);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("input", content);
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            input.put("attachments", attachmentIds.stream().map(UUID::toString).toList());
        }
        run.setInput(objectMapper.valueToTree(input));
        run.setSteps(0);
        run.setCreatedAt(now);
        workflowRunRepository.save(run);
        message.setRunId(run.getId());
        messageRepository.save(message);
        session.touch(now);
        sessionRepository.save(session);
        UUID runId = run.getId();
        UUID userId = session.getUserId();
        UUID workflowId = workflow.getId();
        if (dispatchProperties.enabled()) {
            dispatchTasks.enqueueProcess(sessionId, runId);
        } else {
            // Reserve the bounded local queue before commit, but never execute uncommitted input.
            CompletableFuture<Boolean> committed = new CompletableFuture<>();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    committed.complete(status == STATUS_COMMITTED);
                }
            });
            sessionDispatcher.submit(sessionId.toString(), () -> {
                if (committed.join()) {
                    runCoordinator.process(runId, workflowId, userId, sessionId, content, attachmentIds);
                }
            });
        }
        return new Accepted(message.getId(), runId, "QUEUED");
    }

    private Accepted acceptedExisting(MessageEntity message) {
        if (message.getRunId() == null) {
            throw new BizException(ErrorCode.CONFLICT, "相同 clientMsgId 的消息正在处理中");
        }
        return new Accepted(message.getId(), message.getRunId(), "QUEUED");
    }

    public record Accepted(UUID messageId, UUID runId, String status) {
    }
}
