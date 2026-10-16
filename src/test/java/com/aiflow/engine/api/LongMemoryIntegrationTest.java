package com.aiflow.engine.api;

import com.aiflow.engine.api.service.LongMemoryService;
import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.memory.HistoryOptions;
import com.aiflow.engine.memory.SessionContextProvider;
import com.aiflow.engine.memory.longterm.LongMemoryOptions;
import com.aiflow.engine.memory.longterm.MemoryKind;
import com.aiflow.engine.memory.longterm.MemorySource;
import com.aiflow.engine.memory.longterm.UserMemoryVectorStore;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.entity.SessionEntity;
import com.aiflow.engine.persistence.entity.UserEntity;
import com.aiflow.engine.persistence.entity.UserMemoryEntity;
import com.aiflow.engine.persistence.entity.UserMemoryEventEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.SessionRepository;
import com.aiflow.engine.persistence.repo.UserMemoryEventRepository;
import com.aiflow.engine.persistence.repo.UserMemoryRepository;
import com.aiflow.engine.persistence.repo.UserRepository;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatProvider;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 长期记忆的 H2 端到端口径：抽取落库 -> **换一个会话**仍能召回并注入 -> 人工优先 -> 硬删 -> 模型不一致。
 *
 * <p>为什么用「脚本化 provider」而不是现成的 mock：抽取要求严格 JSON 输出，而 mock 会回显提示词 ——
 * 那样只能测出「解析失败也不崩」。脚本化 provider 让**成功路径**也进得来，
 * 于是「换会话还能记住」这句话才被真的断言，而不是只断言「没报错」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LongMemoryIntegrationTest {

    @DynamicPropertySource
    static void longMemoryProperties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.provider.mode", () -> "auto");
        registry.add("aiflow.provider.order", () -> "scripted");
        // 抽取走「优先便宜档」（它是维护性调用）：不把 scripted 声明成便宜档的话，
        // cheapFirst 会把 mock 提到前面，抽取调用就落到 mock 上（回显提示词 -> 解析失败）
        registry.add("aiflow.provider.cheap-tiers", () -> "scripted");
        registry.add("aiflow.long-memory.extract-enabled", () -> "true");
        registry.add("aiflow.long-memory.extract-min-messages", () -> "1");
        // mock embedding（确定性哈希）的余弦量纲与真实模型不同，阈值必须按它校准着写
        registry.add("aiflow.long-memory.recall-min-score", () -> "0.05");
    }

    @TestConfiguration
    static class ScriptedProviderConfiguration {

        @Bean
        ScriptedChatProvider scriptedChatProvider() {
            return new ScriptedChatProvider();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScriptedChatProvider scripted;

    @Autowired
    private LongMemoryService longMemoryService;

    @Autowired
    private SessionContextProvider contextProvider;

    @Autowired
    private TokenBudgetService tokenBudgetService;

    @Autowired
    private UserMemoryVectorStore vectorStore;

    @Autowired
    private UserMemoryRepository memoryRepository;

    @Autowired
    private UserMemoryEventRepository eventRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        scripted.reset();
        userId = UUID.randomUUID();
        userRepository.save(new UserEntity(userId, "long-memory-it-" + userId, "长期记忆用例用户", Instant.now()));
        sessionId = newSession();
    }

    @Test
    void extractedEntrySurvivesIntoAnotherSession() throws Exception {
        appendDialogue();

        longMemoryService.extractNow(sessionId, userId, UUID.randomUUID());

        List<UserMemoryEntity> entries = memoryRepository.findByUserId(userId);
        assertThat(entries).hasSize(1);
        UserMemoryEntity entry = entries.get(0);
        assertThat(entry.getKind()).isEqualTo(MemoryKind.FACT);
        assertThat(entry.getMemKey()).isEqualTo("公司报销规则");
        assertThat(entry.getValue()).isEqualTo("报销要附行程单");
        assertThat(entry.getSource()).isEqualTo(MemorySource.EXTRACTED);
        assertThat(entry.getConfidence()).isEqualTo(0.9);
        assertThat(entry.getEmbeddingModel()).isEqualTo("mock-embedding");
        assertThat(eventRepository.countByMemoryId(entry.getId())).as("落库要留一条 CREATED 流水").isEqualTo(1);
        assertThat(tokenBudgetService.snapshot(userId).used())
                .as("抽取的对话 token 与逐条 embedding 都要计入用户额度").isPositive();
        assertThat(scripted.extractionCalls()).isEqualTo(1);

        // 换一个会话：用户昨天说过的事，今天新开一段对话仍然要记得
        UUID anotherSession = newSession();
        List<ChatMessage> assembled = contextProvider.assemble(anotherSession, userId,
                ChatMessage.user("报销要附什么材料"), HistoryOptions.inherit(8), LongMemoryOptions.inherit());

        assertThat(assembled).hasSize(2);
        assertThat(assembled.get(0).role()).isEqualTo("system");
        assertThat(assembled.get(0).content()).startsWith(SessionContextProvider.LONG_MEMORY_LABEL)
                .contains("[FACT] 公司报销规则：报销要附行程单");
        assertThat(assembled.get(1).content()).isEqualTo("报销要附什么材料");
        assertThat(memoryRepository.findById(entry.getId()).orElseThrow().getHits())
                .as("被召回一次就要记一次 hits").isEqualTo(1);

        JsonNode preview = preview("报销要附什么材料");
        assertThat(preview.path("hits")).hasSize(1);
        assertThat(preview.path("hits").get(0).path("injected").asBoolean()).isTrue();
        assertThat(preview.path("injectedText").asText()).contains("报销要附行程单");
        assertThat(preview.path("threshold").asDouble()).isEqualTo(0.05);

        // 装配预览是真实栈上唯一能断言「模型到底收到了什么」的出口：它必须也带上长期记忆
        // （真实栈脚本就是这么抓到它在撒谎的），而**看一眼不算一次使用**（hits 不能因预览而变）。
        JsonNode sessionPreview = unwrap(mockMvc.perform(get("/api/v1/sessions/" + anotherSession + "/memory/preview")
                .param("input", "报销要附什么材料"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(sessionPreview.path("longMemoryIncluded").asBoolean()).isTrue();
        assertThat(sessionPreview.path("messages").get(0).path("content").asText())
                .startsWith(SessionContextProvider.LONG_MEMORY_LABEL);
        assertThat(sessionPreview.path("summaryIncluded").asBoolean()).isFalse();
        assertThat(sessionPreview.path("historyCount").asInt()).isZero();
        assertThat(memoryRepository.findById(entry.getId()).orElseThrow().getHits())
                .as("预览不回写 hits").isEqualTo(1);
    }

    @Test
    void manualEntryIsNeverOverwrittenByExtraction() {
        LongMemoryService.MemoryView manual = longMemoryService.create(userId, "FACT", "公司报销规则",
                "报销必须附行程单和发票");
        appendDialogue();
        scripted.reply("""
                [{"kind":"FACT","key":"公司 报销规则","value":"报销不用附行程单","confidence":0.95,
                  "evidence":"现在不用附了","supersedes":true}]
                """);

        longMemoryService.extractNow(sessionId, userId, UUID.randomUUID());

        UserMemoryEntity stored = memoryRepository.findById(manual.id()).orElseThrow();
        assertThat(stored.getValue()).as("人工录入永不被抽取覆盖（人工优先）").isEqualTo("报销必须附行程单和发票");
        assertThat(stored.getSource()).isEqualTo(MemorySource.MANUAL);
        assertThat(eventRepository.findByUserIdOrderByAtDescIdDesc(userId, PageRequest.of(0, 10)))
                .extracting((UserMemoryEventEntity event) -> event.getAction() + ":" + event.getDetail())
                .contains("REJECTED:manual-entry");
    }

    @Test
    void deleteRemovesTheEntryAndItsEvents() throws Exception {
        LongMemoryService.MemoryView created = longMemoryService.create(userId, "PREFERENCE", "回答长度", "三行以内");
        assertThat(eventRepository.countByMemoryId(created.id())).isEqualTo(1);

        JsonNode deleted = unwrap(mockMvc.perform(delete("/api/v1/users/" + userId + "/memories/" + created.id()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(deleted.path("deleted").asBoolean()).isTrue();

        assertThat(memoryRepository.findByUserId(userId)).isEmpty();
        assertThat(eventRepository.countByMemoryId(created.id())).as("删条目就删它的事件行，不留残留").isZero();
        JsonNode preview = preview("回答长度");
        assertThat(preview.path("hits")).isEmpty();
    }

    @Test
    void entriesEmbeddedByAnotherModelAreNotRecalledAndThePreviewSaysWhy() throws Exception {
        LongMemoryService.MemoryView created = longMemoryService.create(userId, "FACT", "公司报销规则", "报销要附行程单");
        // 模拟「换了 embedding 链之后旧向量还在」：模型标记对不上，就不该参与余弦
        vectorStore.upsert(userId, created.id(), "other-model", new float[1024]);

        JsonNode preview = preview("报销要附行程单");

        assertThat(preview.path("hits")).isEmpty();
        assertThat(preview.path("notes").toString()).contains("stale-embeddings:other-model");
    }

    @Test
    void aBrokenModelReplyDoesNotWriteAnythingAndDoesNotBlowUp() {
        appendDialogue();
        scripted.reply("抱歉，我无法完成这个请求。");

        assertThatCode(() -> longMemoryService.extractNow(sessionId, userId, UUID.randomUUID()))
                .doesNotThrowAnyException();

        assertThat(memoryRepository.findByUserId(userId)).isEmpty();
    }

    @Test
    void listingFiltersByKindAndRejectsUnknownKind() throws Exception {
        longMemoryService.create(userId, "FACT", "公司报销规则", "报销要附行程单");
        longMemoryService.create(userId, "PREFERENCE", "回答长度", "三行以内");

        JsonNode facts = getData("/api/v1/users/" + userId + "/memories?kind=fact");
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).path("memKey").asText()).isEqualTo("公司报销规则");
        assertThat(facts.get(0).path("embeddingFresh").asBoolean()).isTrue();

        mockMvc.perform(get("/api/v1/users/" + userId + "/memories?kind=MOOD"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/users/" + UUID.randomUUID() + "/memories"))
                .andExpect(status().isNotFound());
    }

    private UUID newSession() {
        SessionEntity session = new SessionEntity(UUID.randomUUID(), userId, "长期记忆用例会话", Instant.now());
        sessionRepository.save(session);
        return session.getId();
    }

    private void appendDialogue() {
        Instant base = Instant.now().plusSeconds(1);
        messageRepository.save(new MessageEntity(UUID.randomUUID(), sessionId, userId, "USER", "TEXT",
                "我们公司报销要附行程单", null, null, base));
        messageRepository.save(new MessageEntity(UUID.randomUUID(), sessionId, userId, "ASSISTANT", "TEXT",
                "好的，记住了", null, null, base.plusMillis(1)));
    }

    /** 预览接口的入参用 param() 而不是拼 URL：中文拼进 URI 模板会被当成非法字符（M4-1 踩过）。 */
    private JsonNode preview(String input) throws Exception {
        return unwrap(mockMvc.perform(get("/api/v1/users/" + userId + "/memories/preview").param("input", input))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getData(String path) throws Exception {
        return unwrap(mockMvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode unwrap(String body) throws Exception {
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }

    /** 脚本化对话档：只在抽取提示词上返回预设 JSON，其余请求返回固定回复（本用例不关心它们）。 */
    static class ScriptedChatProvider implements ChatProvider {

        private static final String EXTRACTION_MARKER = "你在维护一份用户的长期记忆";
        private static final String DEFAULT_REPLY = """
                [{"kind":"FACT","key":"公司报销规则","value":"报销要附行程单","confidence":0.9,
                  "evidence":"我们公司报销要附行程单","supersedes":false}]
                """;

        private final AtomicReference<String> reply = new AtomicReference<>(DEFAULT_REPLY);
        private final AtomicInteger extractionCalls = new AtomicInteger();

        void reply(String text) {
            reply.set(text);
        }

        void reset() {
            reply.set(DEFAULT_REPLY);
            extractionCalls.set(0);
        }

        int extractionCalls() {
            return extractionCalls.get();
        }

        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public Set<String> capabilities() {
            return Set.of(Capabilities.CHAT);
        }

        @Override
        public ChatResponse chat(ChatRequest request, Consumer<String> deltaConsumer) {
            List<ChatMessage> messages = request.messages();
            String prompt = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
            if (prompt.contains(EXTRACTION_MARKER)) {
                extractionCalls.incrementAndGet();
                String text = reply.get();
                deltaConsumer.accept(text);
                return new ChatResponse(text, "scripted-1", 120, 40);
            }
            deltaConsumer.accept("脚本化回复");
            return new ChatResponse("脚本化回复", "scripted-1", 10, 4);
        }
    }
}
