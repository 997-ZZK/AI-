package com.aiflow.engine.persistence;

import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.SessionMemoryPort;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.SessionMemoryRepository;
import com.aiflow.engine.provider.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SessionMemoryPort} 的 JPA 实现。
 *
 * <p>**这个类永远不抛异常**：读不到就当没有记忆。理由很实际 —— 记忆是"锦上添花"，
 * 它的问题（表还没迁移、连接抖动、内容异常）不该把用户的正常问答变成 500。
 * 失败会累加 {@link #readFailures()}（暴露在 {@code GET /memory} 里），并在日志里按窗口限流。
 */
@Service
public class JpaSessionMemoryAdapter implements SessionMemoryPort {

    private static final Logger log = LoggerFactory.getLogger(JpaSessionMemoryAdapter.class);
    private static final long LOG_EVERY = 50;

    private final MessageRepository messageRepository;
    private final SessionMemoryRepository memoryRepository;
    private final MemoryProperties properties;
    private final AtomicLong readFailures = new AtomicLong();

    public JpaSessionMemoryAdapter(MessageRepository messageRepository, SessionMemoryRepository memoryRepository,
                                   MemoryProperties properties) {
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.properties = properties;
    }

    @Override
    public long readFailures() {
        return readFailures.get();
    }

    @Override
    public List<ChatMessage> history(UUID sessionId, int limit) {
        if (!properties.enabled() || sessionId == null || limit <= 0) {
            return List.of();
        }
        try {
            // 倒序取最近 N 条，再翻回正序：数据库只需要扫到 N 条就停，不会为了"最后 N 条"读全表
            List<MessageEntity> rows = messageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(
                    sessionId, PageRequest.of(0, limit));
            List<ChatMessage> messages = new ArrayList<>(rows.size());
            for (int index = rows.size() - 1; index >= 0; index--) {
                ChatMessage message = toChatMessage(rows.get(index));
                if (message != null) {
                    messages.add(message);
                }
            }
            return List.copyOf(messages);
        } catch (RuntimeException e) {
            recordFailure(sessionId, "history", e);
            return List.of();
        }
    }

    @Override
    public Optional<String> summary(UUID sessionId) {
        if (properties.summaryOff() || sessionId == null) {
            return Optional.empty();
        }
        try {
            return memoryRepository.findById(sessionId)
                    .map(entity -> entity.getSummary())
                    .filter(text -> text != null && !text.isBlank());
        } catch (RuntimeException e) {
            recordFailure(sessionId, "summary", e);
            return Optional.empty();
        }
    }

    private static ChatMessage toChatMessage(MessageEntity row) {
        String content = row.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        String role = row.getRole() == null ? "" : row.getRole().toUpperCase(Locale.ROOT);
        // 只认 user / assistant：别的角色（system / tool）进窗口只会把不相干的东西当对话历史
        return switch (role) {
            case "USER" -> ChatMessage.user(content);
            case "ASSISTANT" -> ChatMessage.assistant(content);
            default -> null;
        };
    }

    private void recordFailure(UUID sessionId, String what, RuntimeException e) {
        long count = readFailures.incrementAndGet();
        if (count == 1 || count % LOG_EVERY == 0) {
            log.warn("会话记忆读取失败（{}，第 {} 次，降级为无记忆）：session={} {}", what, count, sessionId, e.toString());
        }
    }
}
