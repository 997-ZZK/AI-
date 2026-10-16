package com.aiflow.engine.api.service;

import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.memory.HistoryOptions;
import com.aiflow.engine.memory.MemoryMaintenanceExecutor;
import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.SessionContextProvider;
import com.aiflow.engine.memory.SessionMemoryPort;
import com.aiflow.engine.memory.longterm.LongMemoryOptions;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.entity.SessionEntity;
import com.aiflow.engine.persistence.entity.SessionMemoryEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.SessionMemoryRepository;
import com.aiflow.engine.persistence.repo.SessionRepository;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短期记忆的编排：滚动摘要的旁路维护 + 状态 / 预览 / 重建接口（设计见 docs/memory-design.md）。
 *
 * <p>四条硬口径：
 * <ul>
 *   <li>**失败不影响运行**：摘要出任何问题都只记账（attempts / last_error）与打日志，运行早就成功了；</li>
 *   <li>**单赢家**：推进走条件更新（{@code where summarized_count = :expected}），多实例同时折叠只有一个生效；</li>
 *   <li>**不占会话队列**：跑在 {@link MemoryMaintenanceExecutor} 上，用户下一句话不用等模型生成摘要；</li>
 *   <li>**限额**：token 计入用户额度；额度耗尽就跳过；连续失败到上限就停用该会话（避免坏会话每轮烧一次调用）。</li>
 * </ul>
 */
@Service
public class SessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);
    private static final int PREVIEW_CHARS = 500;
    private static final int PROMPT_MESSAGE_FLOOR = 200;

    private final SessionMemoryRepository memoryRepository;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final ProviderRouter providerRouter;
    private final TokenBudgetService tokenBudgetService;
    private final SessionContextProvider contextProvider;
    private final SessionMemoryPort port;
    private final MemoryMaintenanceExecutor maintenance;
    private final MemoryProperties properties;
    private final RunEventBus eventBus;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public SessionMemoryService(SessionMemoryRepository memoryRepository, MessageRepository messageRepository,
                                SessionRepository sessionRepository, ProviderRouter providerRouter,
                                TokenBudgetService tokenBudgetService, SessionContextProvider contextProvider,
                                SessionMemoryPort port, MemoryMaintenanceExecutor maintenance,
                                MemoryProperties properties, RunEventBus eventBus) {
        this.memoryRepository = memoryRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.providerRouter = providerRouter;
        this.tokenBudgetService = tokenBudgetService;
        this.contextProvider = contextProvider;
        this.port = port;
        this.maintenance = maintenance;
        this.properties = properties;
        this.eventBus = eventBus;
    }

    // ------------------------------------------------------------------ 旁路维护

    /**
     * 运行收口后调一次（{@code RunCoordinator} 在会话锁内调，本方法只做「提交」）。
     *
     * <p>同一会话同时只跑一次折叠：并发的第二次直接放弃 —— 摘要不是实时数据，晚一轮没有代价，
     * 而两次同时读同一段消息会白花一次模型调用。
     */
    public void maybeSummarizeAsync(UUID sessionId, UUID userId, UUID runId) {
        if (properties.summaryOff() || sessionId == null || userId == null) {
            return;
        }
        if (!inFlight.add(sessionId)) {
            return;
        }
        try {
            maintenance.submit(() -> {
                try {
                    summarizeNow(sessionId, userId, runId);
                } finally {
                    inFlight.remove(sessionId);
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(sessionId);
            log.warn("会话 {} 的摘要任务提交失败（不影响运行）：{}", sessionId, e.toString());
        }
    }

    /**
     * 同步执行一次折叠：攒够 chunk-size 就把「窗口之外」的那一段折进摘要。
     *
     * <p>正常路径由 {@link #maybeSummarizeAsync} 异步调用；这里是 public 是为了让测试与运维
     * （{@code POST /memory/rebuild} 后手动重算）能确定性地跑一次，而不是"等一会儿看看"。
     */
    public void summarizeNow(UUID sessionId, UUID userId, UUID runId) {
        try {
            long total = messageRepository.countBySessionId(sessionId);
            int foldable = (int) Math.max(0L, total - properties.windowSize());
            int chunk = properties.summaryChunkSize();
            // 先算「有没有活要干」再碰记忆表：没攒够就一行都不写（否则每个会话第一次运行都会留下一行空记忆）
            if (foldable < chunk) {
                return;
            }
            SessionMemoryEntity memory = loadOrCreate(sessionId);
            int maxAttempts = properties.summaryMaxAttempts();
            if (memory.getAttempts() >= maxAttempts) {
                log.warn("会话 {} 的摘要已连续失败 {} 次（上限 {}），停用该会话的摘要；"
                                + "修好后 POST /api/v1/sessions/{}/memory/rebuild 重置",
                        sessionId, memory.getAttempts(), maxAttempts, sessionId);
                return;
            }
            int already = memory.getSummarizedCount();
            if (already > foldable) {
                // 自愈：消息被删过（或换了更小的窗口）时账本会自相矛盾，宁可重摘要一遍
                log.warn("会话 {} 的摘要计数 {} 超过可折叠上限 {}，重置为 0 重算", sessionId, already, foldable);
                memoryRepository.reset(sessionId, Instant.now());
                already = 0;
            }
            if (foldable - already < chunk) {
                return;
            }
            int to = Math.min(foldable, already + chunk);
            List<MessageEntity> slice = messageRepository.fetchSlice(sessionId, already, to - already);
            if (slice.size() < to - already) {
                // 读到的比账本少：可能有并发删除，这一轮不折（账本保持不动，下次重算）
                log.info("会话 {} 计划折叠 {} 条，实际读到 {} 条，本轮跳过", sessionId, to - already, slice.size());
                return;
            }
            TokenBudgetService.Snapshot budget = tokenBudgetService.snapshot(userId);
            if (budget.exhausted()) {
                log.info("用户 {} 额度已耗尽，跳过会话 {} 的摘要（下次运行再试）", userId, sessionId);
                return;
            }
            String prompt = summaryPrompt(memory.getSummary(), slice);
            ProviderRouter.ChatOutcome outcome = providerRouter.chat(Set.of(Capabilities.CHAT),
                    new ChatRequest(null, List.of(ChatMessage.user(prompt))), null,
                    properties.summaryPreferCheapTiers());
            String summary = truncate(stripToEmpty(outcome.response().content()), properties.summaryMaxChars());
            if (summary.isEmpty()) {
                throw new IllegalStateException("模型返回了空摘要");
            }
            long tokens = Math.max(0L, outcome.response().promptTokens() + outcome.response().completionTokens());
            tokenBudgetService.charge(userId, tokens);
            int updated = memoryRepository.advance(sessionId, already, to, summary, summary.length(),
                    outcome.providerId(), (int) Math.min(Integer.MAX_VALUE, tokens), Instant.now());
            if (updated == 0) {
                // 白花了一次调用，但正确性优先：宁可少折一次，也不能把两段不同的消息拼成一份摘要
                log.info("会话 {} 的摘要已被并发的另一次折叠推进，本次放弃", sessionId);
                return;
            }
            log.info("会话 {} 摘要推进：前 {} 条已折入（{} 字符 / {} tokens / 档位 {}）",
                    sessionId, to, summary.length(), tokens, outcome.providerId());
            publish(runId, sessionId, to, summary.length(), tokens, outcome.providerId());
        } catch (RuntimeException e) {
            log.warn("会话 {} 的摘要生成失败（不影响运行）：{}", sessionId, e.toString());
            recordFailure(sessionId, e);
        }
    }

    /** 摘要提示词：保留事实与约束、丢掉寒暄与推理过程；增量合并（已有摘要 + 本次新增对话）。 */
    String summaryPrompt(String existingSummary, List<MessageEntity> slice) {
        int perMessage = Math.max(PROMPT_MESSAGE_FLOOR,
                properties.summaryMaxInputChars() / Math.max(1, slice.size() + 1));
        StringBuilder builder = new StringBuilder();
        builder.append("你在维护一段对话的长期摘要，供后续轮次当上下文使用。\n")
                .append("保留：人物/对象、目标与约束、已确认的事实与结论、未决问题、用户偏好、关键数字与时间。\n")
                .append("丢弃：寒暄、重复、模型自己的推理过程。\n")
                .append("只输出摘要正文（不超过 ").append(properties.summaryMaxChars()).append(" 字），不要前言后语。\n\n");
        builder.append("已有摘要");
        if (existingSummary == null || existingSummary.isBlank()) {
            builder.append("（无）\n");
        } else {
            builder.append("：\n").append(truncate(existingSummary, properties.summaryMaxChars())).append("\n");
        }
        builder.append("\n本次新增对话：\n");
        for (MessageEntity row : slice) {
            String role = "USER".equalsIgnoreCase(row.getRole()) ? "用户" : "助手";
            builder.append(role).append("：").append(truncate(row.getContent(), perMessage)).append("\n");
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ 接口口径

    public MemoryStatus status(UUID sessionId) {
        requireSession(sessionId);
        SessionMemoryEntity memory = memoryRepository.findById(sessionId).orElse(null);
        long total = messageRepository.countBySessionId(sessionId);
        int summarized = memory == null ? 0 : memory.getSummarizedCount();
        int foldable = (int) Math.max(0L, total - properties.windowSize());
        return new MemoryStatus(
                properties.enabled(), properties.summaryEnabled(), properties.windowSize(),
                total, summarized, foldable, Math.max(0, foldable - summarized),
                memory == null ? null : memory.getSummary(),
                memory == null ? 0 : memory.getSummaryChars(),
                memory == null ? null : memory.getModel(),
                memory == null ? 0 : memory.getTokens(),
                memory == null ? 0 : memory.getAttempts(),
                memory == null ? null : memory.getLastError(),
                memory == null ? null : memory.getUpdatedAt(),
                properties.summaryChunkSize(), properties.summaryMaxAttempts(), port.readFailures());
    }

    /**
     * 装配预览：把「模型下轮到底会看到什么」摊开，否则真实栈只能断言「没报错」。
     *
     * <p>它与真实运行走**同一条装配链路**（长期记忆默认档也一样），唯一区别是召回不回写 hits：
     * 预览要如实反映模型会收到什么，但看一眼不算一次使用。
     */
    public MemoryPreview preview(UUID sessionId, String input) {
        SessionEntity session = requireSession(sessionId);
        String text = input == null || input.isBlank() ? "（预览用的示例输入）" : input;
        List<ChatMessage> assembled = contextProvider.assemble(sessionId, session.getUserId(), ChatMessage.user(text),
                HistoryOptions.inherit(properties.windowSize()), LongMemoryOptions.inherit(), false);
        List<PreviewMessage> messages = new ArrayList<>(assembled.size());
        boolean summaryIncluded = false;
        boolean longMemoryIncluded = false;
        boolean profileIncluded = false;
        for (ChatMessage message : assembled) {
            String content = message.content() == null ? "" : message.content();
            // 必须同时满足 role=system 与标签前缀：用户/助手正文即使以同名标签开头，
            // 仍是普通历史消息，不能被误算成上下文块（也不能把 historyCount 减成负数）。
            boolean system = "system".equalsIgnoreCase(message.role());
            if (system && content.startsWith(SessionContextProvider.SUMMARY_LABEL)) {
                summaryIncluded = true;
            } else if (system && content.startsWith(SessionContextProvider.LONG_MEMORY_LABEL)) {
                longMemoryIncluded = true;
            } else if (system && content.startsWith(SessionContextProvider.PROFILE_PREFERENCE_LABEL)) {
                profileIncluded = true;
            }
            messages.add(new PreviewMessage(message.role(), content.length(), content.length() > PREVIEW_CHARS,
                    truncate(content, PREVIEW_CHARS)));
        }
        // 历史条数按**装配形状**数，不按「内容不等于本轮输入」数：用户连着说两句「继续」时，
        // 上一轮那条同样内容会被当成"本轮输入"排除掉、少报一条（真实栈控制台手验就撞上了）。
        // 装配结果永远是 [画像偏好?] + [长期记忆?] + [摘要?] + 历史 + 本轮输入，减掉前缀块就是历史。
        int history = assembled.size() - 1 - (summaryIncluded ? 1 : 0) - (longMemoryIncluded ? 1 : 0)
                - (profileIncluded ? 1 : 0);
        return new MemoryPreview(sessionId, text, summaryIncluded, longMemoryIncluded, profileIncluded, history,
                messages);
    }

    /** 重置摘要（换档位 / 改配置后用）；token 累计值保留 —— 那是已经花掉的钱，不该被重置抹掉。 */
    public MemoryStatus rebuild(UUID sessionId) {
        requireSession(sessionId);
        loadOrCreate(sessionId);
        memoryRepository.reset(sessionId, Instant.now());
        log.info("会话 {} 的摘要已重置，下次运行收口后重算", sessionId);
        return status(sessionId);
    }

    // ------------------------------------------------------------------ 内部

    /** 预览要按会话找到 userId（长期记忆的作用域是用户，不是会话）。 */
    private SessionEntity requireSession(UUID sessionId) {
        if (sessionId == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId);
        }
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
    }

    private SessionMemoryEntity loadOrCreate(UUID sessionId) {
        return memoryRepository.findById(sessionId).orElseGet(() -> {
            try {
                return memoryRepository.saveAndFlush(new SessionMemoryEntity(sessionId, Instant.now()));
            } catch (DataIntegrityViolationException e) {
                // 并发创建：另一个线程先插进去了，读它的
                return memoryRepository.findById(sessionId)
                        .orElseThrow(() -> new IllegalStateException("会话摘要行创建失败: " + sessionId, e));
            }
        });
    }

    private void recordFailure(UUID sessionId, RuntimeException e) {
        String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
        try {
            memoryRepository.recordFailure(sessionId, truncate(detail, 400), Instant.now());
        } catch (RuntimeException ignored) {
            // 连失败都记不下来（表没了 / 连接断了）：只能打日志，绝不能往上抛
            log.warn("会话 {} 的摘要失败也没能记账", sessionId);
        }
    }

    private void publish(UUID runId, UUID sessionId, int summarizedCount, int chars, long tokens, String model) {
        if (runId == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId.toString());
        data.put("summarizedCount", summarizedCount);
        data.put("summaryChars", chars);
        data.put("tokens", tokens);
        data.put("model", model);
        try {
            // 尽力而为：摘要通常发生在运行终态之后，SSE 客户端多半已经断开；
            // 真正的观测口径是 GET /api/v1/sessions/{id}/memory（见 docs/memory-design.md §3.5）
            eventBus.publish(RunEvent.of(runId.toString(), RunEventType.MEMORY_SUMMARIZED, null, data));
        } catch (RuntimeException e) {
            log.debug("摘要事件发布失败（无妨）：{}", e.toString());
        }
    }

    private static String stripToEmpty(String text) {
        return text == null ? "" : text.strip();
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    public record MemoryStatus(boolean enabled, boolean summaryEnabled, int windowSize, long totalMessages,
                               int summarizedCount, int foldableCount, int pendingFold, String summary,
                               int summaryChars, String model, int tokens, int attempts, String lastError,
                               Instant updatedAt, int summaryChunkSize, int summaryMaxAttempts, long readFailures) {
    }

    public record MemoryPreview(UUID sessionId, String input, boolean summaryIncluded, boolean longMemoryIncluded,
                                boolean profileIncluded, int historyCount, List<PreviewMessage> messages) {
    }

    public record PreviewMessage(String role, int chars, boolean truncated, String content) {
    }
}
