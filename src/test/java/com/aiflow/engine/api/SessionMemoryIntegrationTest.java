package com.aiflow.engine.api;

import com.aiflow.engine.api.service.SessionMemoryService;
import com.aiflow.engine.memory.HistoryOptions;
import com.aiflow.engine.memory.SessionContextProvider;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.entity.SessionEntity;
import com.aiflow.engine.persistence.entity.SessionMemoryEntity;
import com.aiflow.engine.persistence.entity.UserEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.SessionMemoryRepository;
import com.aiflow.engine.persistence.repo.SessionRepository;
import com.aiflow.engine.persistence.repo.UserRepository;
import com.aiflow.engine.provider.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 短期记忆的 H2 端到端口径（窗口注入 + 滚动摘要 + 三个接口）。
 *
 * <p>窗口本配置成 8 条、chunk 6 条：14 条以内不折叠，攒到 16 条时正好折掉最老的 6 条 ——
 * 于是"折了哪一段"可以用消息内容逐条断言，而不是只看"有没有摘要"。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionMemoryIntegrationTest {

    @DynamicPropertySource
    static void memoryProperties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.memory.enabled", () -> "true");
        registry.add("aiflow.memory.window-size", () -> "8");
        registry.add("aiflow.memory.summary-enabled", () -> "true");
        registry.add("aiflow.memory.summary-chunk-size", () -> "6");
        registry.add("aiflow.memory.summary-max-chars", () -> "1200");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionMemoryService sessionMemoryService;

    @Autowired
    private SessionContextProvider contextProvider;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SessionMemoryRepository sessionMemoryRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        userRepository.save(new UserEntity(userId, "memory-it-" + userId, "记忆用例用户", Instant.now()));
        SessionEntity session = new SessionEntity(UUID.randomUUID(), userId, "记忆用例会话", Instant.now());
        sessionRepository.save(session);
        sessionId = session.getId();
    }

    @Test
    void windowTakesTheLatestMessagesInOrderAndSkipsNonDialogueRows() {
        appendMessages(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        messageRepository.save(new MessageEntity(UUID.randomUUID(), sessionId, userId, "SYSTEM", "TEXT",
                "系统提示不该进窗口", null, null, Instant.now().plusMillis(100)));
        messageRepository.save(new MessageEntity(UUID.randomUUID(), sessionId, userId, "ASSISTANT", "TEXT",
                "   ", null, null, Instant.now().plusMillis(200)));

        List<ChatMessage> assembled = contextProvider.assemble(sessionId, ChatMessage.user("本轮"),
                HistoryOptions.inherit(8));

        assertThat(assembled).hasSize(9);   // 8 条窗口 + 本轮
        assertThat(assembled.get(0).content()).as("取最近 8 条，不是最早 8 条").isEqualTo("第3句");
        assertThat(assembled.get(7).content()).isEqualTo("第10句");
        assertThat(assembled.get(8).content()).isEqualTo("本轮");
        assertThat(assembled).noneMatch(message -> message.content().contains("系统提示"));
        assertThat(assembled.subList(0, 8)).extracting(ChatMessage::role)
                .as("角色按库里的 USER / ASSISTANT 还原").containsExactlyElementsOf(
                        List.of("user", "assistant", "user", "assistant", "user", "assistant", "user", "assistant"));
    }

    @Test
    void summaryFoldsExactlyTheOldestChunkAndAdvancesTheWatermark() {
        appendMessages(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        sessionMemoryService.summarizeNow(sessionId, userId, UUID.randomUUID());
        assertThat(sessionMemoryRepository.findById(sessionId))
                .as("16 条之前不该折叠（可折叠 2 条 < chunk 6）").isEmpty();

        appendMessages(11, 12, 13, 14, 15, 16);
        sessionMemoryService.summarizeNow(sessionId, userId, UUID.randomUUID());

        SessionMemoryEntity memory = sessionMemoryRepository.findById(sessionId).orElseThrow();
        assertThat(memory.getSummarizedCount()).as("只折最老的 6 条").isEqualTo(6);
        assertThat(memory.getTokens()).as("摘要调用的 token 要记账").isPositive();
        assertThat(memory.getModel()).isNotBlank();
        String summary = memory.getSummary();
        assertThat(summary).isNotNull().contains("第1句").contains("第6句")
                .as("第 7 句在窗口里，不该被折进摘要").doesNotContain("第7句");

        // 还没攒够下一批：再跑一次不该推进
        sessionMemoryService.summarizeNow(sessionId, userId, UUID.randomUUID());
        assertThat(sessionMemoryRepository.findById(sessionId).orElseThrow().getSummarizedCount()).isEqualTo(6);

        // 再来 6 条：第二段折叠（第 7-12 句）—— 并且是在已有摘要基础上增量合并
        appendMessages(17, 18, 19, 20, 21, 22);
        sessionMemoryService.summarizeNow(sessionId, userId, UUID.randomUUID());
        SessionMemoryEntity advanced = sessionMemoryRepository.findById(sessionId).orElseThrow();
        assertThat(advanced.getSummarizedCount()).isEqualTo(12);
        assertThat(advanced.getSummary()).contains("第12句").as("增量合并：旧摘要也还在").contains("第1句");
    }

    @Test
    void memoryEndpointsExposeStatusPreviewAndRebuild() throws Exception {
        appendMessages(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        sessionMemoryService.summarizeNow(sessionId, userId, UUID.randomUUID());

        JsonNode status = getData("/api/v1/sessions/" + sessionId + "/memory");
        assertThat(status.path("enabled").asBoolean()).isTrue();
        assertThat(status.path("summaryEnabled").asBoolean()).isTrue();
        assertThat(status.path("totalMessages").asLong()).isEqualTo(16);
        assertThat(status.path("foldableCount").asInt()).isEqualTo(8);
        assertThat(status.path("summarizedCount").asInt()).isEqualTo(6);
        assertThat(status.path("pendingFold").asInt()).isEqualTo(2);
        assertThat(status.path("summary").asText()).contains("第1句");

        JsonNode preview = getData("/api/v1/sessions/" + sessionId + "/memory/preview?input=下一句");
        assertThat(preview.path("summaryIncluded").asBoolean()).isTrue();
        assertThat(preview.path("historyCount").asInt()).isEqualTo(8);
        assertThat(preview.path("messages")).hasSize(10);   // 摘要 + 8 条窗口 + 本轮

        JsonNode rebuilt = unwrap(mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/memory/rebuild"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(rebuilt.path("summarizedCount").asInt()).isZero();
        assertThat(rebuilt.path("summary").isNull() || rebuilt.path("summary").isMissingNode()).isTrue();
        assertThat(rebuilt.path("tokens").asInt()).as("token 是已经花掉的钱，重置摘要不该抹掉").isPositive();
    }

    @Test
    void unknownSessionIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/" + UUID.randomUUID() + "/memory"))
                .andExpect(status().isNotFound());
    }

    private void appendMessages(int... indexes) {
        // 时间戳递增是刻意的：(created_at, id) 才是全序，同一微秒的两条只能靠 id 兜底 —— 用例不去踩那个边界
        Instant base = Instant.now().plusSeconds(1);
        for (int index : indexes) {
            String role = index % 2 == 1 ? "USER" : "ASSISTANT";
            messageRepository.save(new MessageEntity(UUID.randomUUID(), sessionId, userId, role, "TEXT",
                    "第" + index + "句", null, null, base.plusMillis(index)));
        }
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
}
