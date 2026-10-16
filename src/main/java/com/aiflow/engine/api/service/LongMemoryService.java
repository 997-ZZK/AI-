package com.aiflow.engine.api.service;

import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.memory.MemoryMaintenanceExecutor;
import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.longterm.LongMemoryProperties;
import com.aiflow.engine.memory.longterm.LongMemoryRecallService;
import com.aiflow.engine.memory.longterm.LongMemoryThrottle;
import com.aiflow.engine.memory.longterm.MemoryCandidate;
import com.aiflow.engine.memory.longterm.MemoryExtractionParser;
import com.aiflow.engine.memory.longterm.MemoryKeys;
import com.aiflow.engine.memory.longterm.MemoryKind;
import com.aiflow.engine.memory.longterm.MemoryMerge;
import com.aiflow.engine.memory.longterm.MemorySource;
import com.aiflow.engine.memory.longterm.MemoryWriteGuard;
import com.aiflow.engine.memory.longterm.RecalledMemory;
import com.aiflow.engine.memory.longterm.UserMemoryVectorStore;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.entity.UserMemoryEntity;
import com.aiflow.engine.persistence.entity.UserMemoryEventEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.UserMemoryEventRepository;
import com.aiflow.engine.persistence.repo.UserMemoryRepository;
import com.aiflow.engine.persistence.repo.UserRepository;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.Vectors;
import com.aiflow.engine.safety.ContentSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆的编排：抽取（旁路）+ 手工维护 + 召回预览（设计见 docs/long-memory-design.md）。
 *
 * <p>五条硬口径：
 * <ul>
 *   <li>**失败不影响运行**：抽取/embedding 出任何问题都只打日志，运行早就成功了；</li>
 *   <li>**单赢家**：写入靠 {@code unique (user_id, kind, mem_key)}，撞约束的那个按拒绝处理，
 *       绝不允许两段对话拼成一条事实；</li>
 *   <li>**人工优先**：{@code MANUAL} 条目永不被抽取覆盖；</li>
 *   <li>**不占会话队列**：跑在 {@link MemoryMaintenanceExecutor} 上，用户下一句话不必等它；</li>
 *   <li>**限额**：抽取的对话 token 与逐条 embedding 都计入用户额度；额度耗尽就跳过抽取。</li>
 * </ul>
 */
@Service
public class LongMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongMemoryService.class);
    private static final int EVENT_DETAIL_MAX = 300;
    private static final int PROMPT_MESSAGE_FLOOR = 120;
    /** 抽取提示词里最多带多少条已有条目（单用户上限 200，全带上会让 prompt 失衡）。 */
    private static final int PROMPT_EXISTING_LIMIT = 40;
    private static final String ACTOR_SYSTEM = "system";
    private static final String ACTOR_MANUAL = "manual";

    private final UserMemoryRepository memoryRepository;
    private final UserMemoryEventRepository eventRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final ProviderRouter providerRouter;
    private final EmbeddingProvider embeddingProvider;
    private final UserMemoryVectorStore vectorStore;
    private final TokenBudgetService tokenBudgetService;
    private final ContentSafety contentSafety;
    private final MemoryMaintenanceExecutor maintenance;
    private final LongMemoryThrottle throttle;
    private final LongMemoryRecallService recallService;
    private final LongMemoryProperties properties;
    private final MemoryProperties memoryProperties;
    private final RunEventBus eventBus;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public LongMemoryService(UserMemoryRepository memoryRepository, UserMemoryEventRepository eventRepository,
                             UserRepository userRepository, MessageRepository messageRepository,
                             ProviderRouter providerRouter, EmbeddingProvider embeddingProvider,
                             UserMemoryVectorStore vectorStore, TokenBudgetService tokenBudgetService,
                             ContentSafety contentSafety, MemoryMaintenanceExecutor maintenance,
                             LongMemoryThrottle throttle, LongMemoryRecallService recallService,
                             LongMemoryProperties properties, MemoryProperties memoryProperties,
                             RunEventBus eventBus) {
        this.memoryRepository = memoryRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.providerRouter = providerRouter;
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
        this.tokenBudgetService = tokenBudgetService;
        this.contentSafety = contentSafety;
        this.maintenance = maintenance;
        this.throttle = throttle;
        this.recallService = recallService;
        this.properties = properties;
        this.memoryProperties = memoryProperties;
        this.eventBus = eventBus;
    }

    // ------------------------------------------------------------------ 旁路抽取

    /**
     * 运行收口后调一次（{@code RunCoordinator} 在会话锁内调，本方法只做「提交」）。
     *
     * <p>in-flight 去重与节流都在这里：同一会话同时只跑一次抽取，节流不通过连任务都不提交。
     */
    public void maybeExtractAsync(UUID sessionId, UUID userId, UUID runId) {
        if (properties.extractOff() || sessionId == null || userId == null) {
            return;
        }
        if (!inFlight.add(sessionId)) {
            return;
        }
        try {
            maintenance.submit(() -> {
                try {
                    extractNow(sessionId, userId, runId);
                } finally {
                    inFlight.remove(sessionId);
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(sessionId);
            log.warn("会话 {} 的长期记忆抽取任务提交失败（不影响运行）：{}", sessionId, e.toString());
        }
    }

    /**
     * 同步执行一次抽取（public 是为了让测试与运维能确定性地跑一次，而不是「等一会儿看看」）。
     *
     * <p>节流在**取窗口之前**判：不通过就连一次库都不查。
     */
    public void extractNow(UUID sessionId, UUID userId, UUID runId) {
        if (properties.extractOff() || sessionId == null || userId == null) {
            return;
        }
        long total = messageRepository.countBySessionId(sessionId);
        LongMemoryThrottle.Decision decision = throttle.start(sessionId, total, Instant.now(), properties);
        if (!decision.extract()) {
            log.debug("会话 {} 跳过长期记忆抽取（{}）", sessionId, decision.reason());
            return;
        }
        try {
            extract(sessionId, userId, runId);
        } catch (RuntimeException e) {
            log.warn("会话 {} 的长期记忆抽取失败（不影响运行）：{}", sessionId, e.toString());
        } finally {
            throttle.finish(sessionId);
        }
    }

    private void extract(UUID sessionId, UUID userId, UUID runId) {
        List<MessageEntity> window = window(sessionId);
        if (window.isEmpty()) {
            return;
        }
        TokenBudgetService.Snapshot budget = tokenBudgetService.snapshot(userId);
        if (budget.exhausted()) {
            log.info("用户 {} 额度已耗尽，跳过长期记忆抽取（下次运行再试）", userId);
            return;
        }
        List<UserMemoryEntity> existing = memoryRepository.findByUserId(userId);
        String prompt = extractionPrompt(window, existing);
        ProviderRouter.ChatOutcome outcome = providerRouter.chat(Set.of(Capabilities.CHAT),
                new ChatRequest(null, List.of(ChatMessage.user(prompt))), null, true);
        long tokens = Math.max(0L, outcome.response().promptTokens() + outcome.response().completionTokens());
        tokenBudgetService.charge(userId, tokens);
        MemoryExtractionParser.Outcome parsed = MemoryExtractionParser.parse(outcome.response().content(), properties,
                text -> !contentSafety.scanRules(text).allowed());
        if (!parsed.rejected().isEmpty()) {
            log.info("会话 {} 的抽取有 {} 条被丢弃：{}", sessionId, parsed.rejected().size(),
                    truncate(parsed.rejected().toString(), 400));
        }
        if (parsed.candidates().isEmpty()) {
            publish(runId, userId, 0, Map.of());
            return;
        }
        Map<String, UserMemoryEntity> byKey = new LinkedHashMap<>();
        existing.forEach(entity -> byKey.putIfAbsent(slot(entity.getKind(), entity.getMemKey()), entity));
        Map<String, Integer> kindCounts = new LinkedHashMap<>();
        int written = 0;
        for (MemoryCandidate candidate : parsed.candidates()) {
            String slot = slot(candidate.kind(), candidate.memKey());
            UserMemoryEntity current = byKey.get(slot);
            MemoryMerge.Decision decision = MemoryMerge.decide(candidate, current == null ? null
                    : new MemoryMerge.Existing(current.getSource(), current.getValue()));
            switch (decision.action()) {
                case CREATE -> {
                    if (applyCreate(sessionId, userId, runId, candidate)) {
                        written++;
                        kindCounts.merge(candidate.kind().name(), 1, Integer::sum);
                    }
                }
                case UPDATE -> {
                    if (applyUpdate(sessionId, userId, runId, candidate, current)) {
                        written++;
                        kindCounts.merge(candidate.kind().name(), 1, Integer::sum);
                    }
                }
                case REJECT -> recordEvent(current == null ? null : current.getId(), userId, "REJECTED",
                        candidate.memKey(), ACTOR_SYSTEM, runId, decision.detail());
            }
        }
        log.info("会话 {} 的长期记忆抽取完成：写入 {} 条（{}），本次调用 {} tokens", sessionId, written, kindCounts, tokens);
        publish(runId, userId, written, kindCounts);
    }

    private boolean applyCreate(UUID sessionId, UUID userId, UUID runId, MemoryCandidate candidate) {
        if (memoryRepository.countByUserId(userId) >= properties.maxEntriesPerUser()) {
            recordEvent(null, userId, "REJECTED", candidate.memKey(), ACTOR_SYSTEM, runId, "over-limit");
            log.warn("用户 {} 的长期记忆已达上限 {}，拒绝新增「{}」（不静默丢，也不自动删用户数据）",
                    userId, properties.maxEntriesPerUser(), candidate.displayKey());
            return false;
        }
        Instant now = Instant.now();
        UserMemoryEntity entity = new UserMemoryEntity(UUID.randomUUID(), userId, candidate.kind(), candidate.memKey(),
                candidate.displayKey(), candidate.value(), candidate.confidence(), MemorySource.EXTRACTED,
                sessionId, runId, candidate.evidence(), now);
        try {
            memoryRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // 并发的另一次抽取先插进去了：丢弃本次（不许两段对话拼成一条事实，见 docs §3.6）
            recordEvent(null, userId, "REJECTED", candidate.memKey(), ACTOR_SYSTEM, runId, "concurrent-create");
            return false;
        }
        embed(userId, entity);
        recordEvent(entity.getId(), userId, "CREATED", candidate.memKey(), ACTOR_SYSTEM, runId,
                "confidence=" + candidate.confidence());
        return true;
    }

    private boolean applyUpdate(UUID sessionId, UUID userId, UUID runId, MemoryCandidate candidate,
                                UserMemoryEntity current) {
        if (current == null) {
            return false;
        }
        current.overwrite(candidate.value(), candidate.confidence(), candidate.evidence(), sessionId, runId,
                Instant.now());
        memoryRepository.saveAndFlush(current);
        embed(userId, current);
        recordEvent(current.getId(), userId, "UPDATED", candidate.memKey(), ACTOR_SYSTEM, runId, "superseded");
        return true;
    }

    /** 向量写入失败**不算写入失败**：条目已经落库（可查可删），只是暂时不可召回，日志里有痕。 */
    private void embed(UUID userId, UserMemoryEntity entity) {
        String text = entity.getKind().name() + " " + entity.getDisplayKey() + "：" + entity.getValue();
        try {
            float[] vector = embeddingProvider.embed(text);
            Vectors.requireDimensions(vector, embeddingProvider.dimensions(), "embedding " + embeddingProvider.id());
            vectorStore.upsert(userId, entity.getId(), embeddingProvider.id(), vector);
            entity.markEmbeddingModel(embeddingProvider.id());
            memoryRepository.saveAndFlush(entity);
            // embedding 接口不返回 usage：按「1 字符 ≈ 1 token」的保守上限记账（宁可高估，不可漏记）
            tokenBudgetService.charge(userId, Math.max(1, text.length()));
        } catch (RuntimeException e) {
            log.warn("长期记忆条目 {} 的向量写入失败（该条暂不可召回）：{}", entity.getId(), e.toString());
        }
    }

    /** 抽取用的窗口：最近 N 条对话（与 M4-1 同一份读法，只认 USER / ASSISTANT、跳过空内容）。 */
    private List<MessageEntity> window(UUID sessionId) {
        int size = Math.max(1, memoryProperties.windowSize());
        List<MessageEntity> rows = messageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(sessionId,
                PageRequest.of(0, size));
        List<MessageEntity> ordered = new ArrayList<>(rows);
        Collections.reverse(ordered);
        List<MessageEntity> window = new ArrayList<>();
        for (MessageEntity row : ordered) {
            String role = row.getRole() == null ? "" : row.getRole().toUpperCase(Locale.ROOT);
            if (!"USER".equals(role) && !"ASSISTANT".equals(role)) {
                continue;
            }
            if (row.getContent() == null || row.getContent().isBlank()) {
                continue;
            }
            window.add(row);
        }
        return window;
    }

    /**
     * 抽取提示词。三条要求必须在里面：
     * <ol>
     *   <li>禁写清单（代码里还会再过一遍，这里是第一道）；</li>
     *   <li>**带上已有条目的 key 与正文** —— 不给出旧值，模型判不了「这是纠正还是另一件事」，
     *       归并规则就退化成「新的一定赢」；</li>
     *   <li>严格 JSON、无内容输出 []。</li>
     * </ol>
     */
    String extractionPrompt(List<MessageEntity> window, List<UserMemoryEntity> existing) {
        List<UserMemoryEntity> shown = existing.stream().limit(PROMPT_EXISTING_LIMIT).toList();
        int perMessage = Math.max(PROMPT_MESSAGE_FLOOR,
                memoryProperties.summaryMaxInputChars() / Math.max(1, window.size() + shown.size() + 1));
        StringBuilder builder = new StringBuilder();
        builder.append("你在维护一份用户的长期记忆（跨会话复用）。只抽取用户明确说过、之后仍然成立的内容。\n\n")
                .append("允许两类：\n")
                .append("- FACT：客观事实（环境、项目、约束、术语、流程）\n")
                .append("- PREFERENCE：用户对回答方式的要求（语言、长度、格式、称呼、是否举例）\n\n")
                .append("禁止写入（命中就丢弃）：健康与医疗、政治与宗教倾向、性取向、财务账号与密码、")
                .append("任何 token 或密钥、身份证/手机号/邮箱/住址等可识别信息、关于第三方的信息、你自己的推测与常识。\n\n")
                .append("判断「纠正」还是「新事实」：若下面的已有条目说的是同一件事、而用户这轮给了新说法，")
                .append("把它标 supersedes=true 且 value 写**用户的新说法**；否则用新的 key、supersedes=false。\n\n")
                .append("只输出 JSON 数组，不要解释、不要代码块围栏；没有可抽取的内容就输出 []。每项字段：\n")
                .append("{\"kind\":\"FACT|PREFERENCE\",\"key\":\"简短可复用的名字\",\"value\":\"内容\",")
                .append("\"confidence\":0~1,\"evidence\":\"原文片段\",\"supersedes\":false}\n")
                .append("最多 ").append(properties.extractMaxCandidates()).append(" 条；value 不超过 ")
                .append(properties.extractMaxChars()).append(" 字；没有把握就把 confidence 写到 ")
                .append(properties.extractMinConfidence()).append(" 以下。\n\n");
        builder.append("本轮对话（最近 ").append(window.size()).append(" 条）：\n");
        for (MessageEntity row : window) {
            String role = "USER".equalsIgnoreCase(row.getRole()) ? "用户" : "助手";
            builder.append(role).append("：").append(truncate(row.getContent(), perMessage)).append("\n");
        }
        builder.append("\n已有条目（key：正文）：\n");
        if (shown.isEmpty()) {
            builder.append("（无）\n");
        } else {
            for (UserMemoryEntity entity : shown) {
                builder.append("- ").append(entity.getDisplayKey()).append("：")
                        .append(truncate(entity.getValue(), properties.extractMaxChars())).append("\n");
            }
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ 接口口径

    public List<MemoryView> list(UUID userId, String kindParam, int limit) {
        requireUser(userId);
        int size = Math.min(Math.max(1, limit), 500);
        MemoryKind kind = kindParam == null || kindParam.isBlank() ? null : MemoryKind.parse(kindParam);
        if (kindParam != null && !kindParam.isBlank() && kind == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "kind 只能是 FACT 或 PREFERENCE: " + kindParam);
        }
        List<UserMemoryEntity> rows = kind == null
                ? memoryRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId, PageRequest.of(0, size))
                : memoryRepository.findByUserIdAndKindOrderByUpdatedAtDescIdDesc(userId, kind, PageRequest.of(0, size));
        return rows.stream().map(this::toView).toList();
    }

    /** 手工新增 / 纠正：{@code source = MANUAL}、{@code confidence = 1.00}，此后抽取再也覆盖不了它。 */
    @Transactional
    public MemoryView create(UUID userId, String kindParam, String key, String value) {
        requireUser(userId);
        MemoryKind kind = MemoryKind.parse(kindParam);
        if (kind == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "kind 只能是 FACT 或 PREFERENCE: " + kindParam);
        }
        String displayKey = key == null ? "" : key.strip();
        String memKey = MemoryKeys.normalize(displayKey);
        String text = value == null ? "" : value.strip();
        if (memKey.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "key 不能为空（归一化后为空）");
        }
        if (text.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "value 不能为空");
        }
        if (text.length() > properties.extractMaxChars()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "value 超过 " + properties.extractMaxChars() + " 字上限");
        }
        String guard = MemoryWriteGuard.check(displayKey + " " + text);
        if (guard != null) {
            // 手工写入**报错**而不是静默丢弃：这是用户的显式动作，必须让他知道为什么没存进去
            throw new BizException(ErrorCode.BAD_REQUEST, "这条内容属于禁写类别（" + guard + "），不进入长期记忆");
        }
        Instant now = Instant.now();
        Optional<UserMemoryEntity> existing = memoryRepository.findByUserIdAndKindAndMemKey(userId, kind, memKey);
        UserMemoryEntity entity;
        String action;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.overwriteByHand(displayKey, memKey, text, now);
            action = "UPDATED";
        } else {
            if (memoryRepository.countByUserId(userId) >= properties.maxEntriesPerUser()) {
                throw new BizException(ErrorCode.CONFLICT,
                        "长期记忆条目已达上限 " + properties.maxEntriesPerUser() + "，请先删除不再需要的条目");
            }
            entity = new UserMemoryEntity(UUID.randomUUID(), userId, kind, memKey, displayKey, text, 1.0,
                    MemorySource.MANUAL, null, null, null, now);
            action = "CREATED";
        }
        memoryRepository.saveAndFlush(entity);
        embed(userId, entity);
        recordEvent(entity.getId(), userId, action, memKey, ACTOR_MANUAL, null, "manual");
        log.info("用户 {} 手工{}了一条长期记忆：{}", userId, "CREATED".equals(action) ? "新增" : "纠正", entity.getId());
        return toView(entity);
    }

    /** 硬删该行 + 同事务清掉它的事件行（用户说「删掉」就该删干净）。 */
    @Transactional
    public void delete(UUID userId, UUID memoryId) {
        requireUser(userId);
        UserMemoryEntity entity = memoryRepository.findById(memoryId)
                .filter(row -> row.getUserId().equals(userId))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "长期记忆不存在: " + memoryId));
        eventRepository.deleteByMemoryId(memoryId);
        memoryRepository.delete(entity);
        try {
            vectorStore.remove(memoryId);
        } catch (RuntimeException e) {
            log.warn("长期记忆 {} 的向量清理失败（行已删除）：{}", memoryId, e.toString());
        }
        log.info("用户 {} 删除了一条长期记忆：{}", userId, memoryId);
    }

    /** 召回预览：把「命中了哪几条、打多少分、为什么被丢掉」摊开（没有它，真实栈只能断言「没报错」）。 */
    public RecallPreview preview(UUID userId, String input, int topK) {
        requireUser(userId);
        String text = input == null || input.isBlank() ? "（预览用的示例输入）" : input.strip();
        int width = topK <= 0 ? properties.recallTopK() : Math.min(topK, 50);
        LongMemoryRecallService.RecallOutcome outcome = recallService.recallDetailed(userId, text, width, false);
        Set<UUID> injectedIds = new LinkedHashSet<>();
        outcome.injected().forEach(recalled -> injectedIds.add(recalled.id()));
        List<PreviewHit> hits = new ArrayList<>();
        for (RecalledMemory recalled : outcome.candidates()) {
            boolean injected = injectedIds.contains(recalled.id());
            hits.add(new PreviewHit(recalled.id(), recalled.kind().name(), recalled.displayKey(), recalled.value(),
                    recalled.score(), injected, recalled.render().length()));
        }
        List<PreviewDrop> dropped = outcome.dropped().stream()
                .map(drop -> new PreviewDrop(drop.id(), drop.displayKey(), drop.score(), drop.reason()))
                .toList();
        return new RecallPreview(userId, outcome.query(), outcome.threshold(), width,
                properties.enabled() && properties.recallEnabled(), properties.recallMaxChars(),
                List.copyOf(hits), dropped, outcome.notes(), renderBlock(outcome.injected()));
    }

    static String renderBlock(List<RecalledMemory> recalled) {
        return RecalledMemory.renderAll(recalled);
    }

    // ------------------------------------------------------------------ 内部

    private void requireUser(UUID userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + userId);
        }
    }

    private MemoryView toView(UserMemoryEntity entity) {
        boolean fresh = entity.getEmbeddingModel() != null && entity.getEmbeddingModel().equals(embeddingProvider.id());
        return new MemoryView(entity.getId(), entity.getKind().name(), entity.getDisplayKey(), entity.getMemKey(),
                entity.getValue(), entity.getConfidence(), entity.getSource().name(), entity.getEvidence(),
                entity.getHits(), entity.getLastUsedAt(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getEmbeddingModel(), fresh);
    }

    private void recordEvent(UUID memoryId, UUID userId, String action, String memKey, String actor, UUID runId,
                             String detail) {
        try {
            UUID id = memoryId == null ? UUID.randomUUID() : memoryId;
            eventRepository.save(new UserMemoryEventEntity(UUID.randomUUID(), id,
                    userId, action, truncate(memKey, 128), actor, runId, truncate(detail, EVENT_DETAIL_MAX),
                    Instant.now()));
        } catch (RuntimeException e) {
            // 流水是观测，不是主链路：记不下来只打日志
            log.debug("长期记忆事件写入失败（无妨）：{}", e.toString());
        }
    }

    private void publish(UUID runId, UUID userId, int written, Map<String, Integer> kindCounts) {
        if (runId == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("written", written);
        data.put("kinds", kindCounts);
        try {
            // 只带计数与类别分布：**不含正文**（事件流会进 SSE 与控制台，记忆正文属于用户隐私）
            eventBus.publish(RunEvent.of(runId.toString(), RunEventType.MEMORY_EXTRACTED, null, data));
        } catch (RuntimeException e) {
            log.debug("长期记忆事件发布失败（无妨）：{}", e.toString());
        }
    }

    private static String slot(MemoryKind kind, String memKey) {
        return kind.name() + "|" + memKey;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    public record MemoryView(UUID id, String kind, String displayKey, String memKey, String value, double confidence,
                             String source, String evidence, int hits, Instant lastUsedAt, Instant createdAt,
                             Instant updatedAt, String embeddingModel, boolean embeddingFresh) {
    }

    public record RecallPreview(UUID userId, String query, double threshold, int topK, boolean injectionEnabled,
                                int maxChars, List<PreviewHit> hits, List<PreviewDrop> dropped, List<String> notes,
                                String injectedText) {
    }

    public record PreviewHit(UUID id, String kind, String displayKey, String value, double score, boolean injected,
                             int chars) {
    }

    public record PreviewDrop(UUID id, String displayKey, double score, String reason) {
    }
}
