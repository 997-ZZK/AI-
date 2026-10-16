package com.aiflow.engine.api.service;

import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.memory.MemoryMaintenanceExecutor;
import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.SessionContextProvider;
import com.aiflow.engine.memory.SessionMemoryPort;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.entity.SessionMemoryEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.SessionMemoryRepository;
import com.aiflow.engine.persistence.repo.SessionRepository;
import com.aiflow.engine.persistence.entity.SessionEntity;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import com.aiflow.engine.provider.ProviderRouter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 滚动摘要的**失败与并发口径**（不碰 DB 的单元用例）。
 *
 * <p>这一组用例守的是"摘要坏了会怎样"：运行必须照跑、账要记得住、额度要扣得明、
 * 输的那个赢家不能把两段消息拼成一份摘要。它们全是分支，靠端到端用例覆盖不到。
 */
class SessionMemoryServiceTest {

    private static final MemoryProperties PROPERTIES =
            new MemoryProperties(true, 8, 4000, true, 6, 8000, 1200, true, 5);

    private final SessionMemoryRepository memoryRepository = mock(SessionMemoryRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final ProviderRouter providerRouter = mock(ProviderRouter.class);
    private final TokenBudgetService tokenBudgetService = mock(TokenBudgetService.class);
    private final MemoryMaintenanceExecutor maintenance = mock(MemoryMaintenanceExecutor.class);
    private final RunEventBus eventBus = mock(RunEventBus.class);
    private final SessionMemoryPort port = SessionMemoryPort.disabled();
    private final SessionMemoryService service = new SessionMemoryService(memoryRepository, messageRepository,
            sessionRepository, providerRouter, tokenBudgetService,
            new SessionContextProvider(port, PROPERTIES), port, maintenance, PROPERTIES, eventBus);

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    void foldsTheOldestChunkWithOneConditionalUpdateAndPublishesTheEvent() {
        givenMemory(0, null);
        when(messageRepository.countBySessionId(sessionId)).thenReturn(16L);
        when(messageRepository.fetchSlice(sessionId, 0, 6)).thenReturn(rows(1, 6));
        when(tokenBudgetService.snapshot(userId)).thenReturn(normalBudget());
        when(providerRouter.chat(any(), any(), any(), anyBoolean()))
                .thenReturn(new ProviderRouter.ChatOutcome(new ChatResponse("摘要正文", "mock", 10, 5), "mock",
                        List.of(), List.of()));
        when(memoryRepository.advance(eq(sessionId), eq(0), eq(6), eq("摘要正文"), eq(4), eq("mock"), eq(15), any()))
                .thenReturn(1);

        service.summarizeNow(sessionId, userId, runId);

        verify(memoryRepository).advance(eq(sessionId), eq(0), eq(6), eq("摘要正文"), eq(4), eq("mock"), eq(15), any());
        verify(tokenBudgetService).charge(userId, 15);
        ArgumentCaptor<RunEvent> event = ArgumentCaptor.forClass(RunEvent.class);
        verify(eventBus).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(RunEventType.MEMORY_SUMMARIZED);
        assertThat(event.getValue().runId()).isEqualTo(runId.toString());
        assertThat(event.getValue().data()).containsEntry("summarizedCount", 6).containsEntry("tokens", 15L);
        assertThat(event.getValue().data()).as("事件里不许带摘要正文").doesNotContainKey("summary");
    }

    @Test
    void modelFailureIsRecordedAndNeverEscapes() {
        givenMemory(0, null);
        when(messageRepository.countBySessionId(sessionId)).thenReturn(16L);
        when(messageRepository.fetchSlice(sessionId, 0, 6)).thenReturn(rows(1, 6));
        when(tokenBudgetService.snapshot(userId)).thenReturn(normalBudget());
        when(providerRouter.chat(any(), any(), any(), anyBoolean())).thenThrow(new IllegalStateException("模型挂了"));

        assertThatCode(() -> service.summarizeNow(sessionId, userId, runId)).doesNotThrowAnyException();

        verify(memoryRepository).recordFailure(eq(sessionId), contains("IllegalStateException"), any());
        verify(memoryRepository, never()).advance(any(), anyInt(), anyInt(), anyString(), anyInt(), anyString(),
                anyInt(), any());
        verify(tokenBudgetService, never()).charge(any(), anyLong());
    }

    @Test
    void stopsTryingAfterTooManyFailures() {
        givenMemory(5, 0, "上一次也失败了");

        service.summarizeNow(sessionId, userId, runId);

        verifyNoInteractions(providerRouter);
        verify(memoryRepository, never()).advance(any(), anyInt(), anyInt(), anyString(), anyInt(), anyString(),
                anyInt(), any());
    }

    @Test
    void skipsWhenTheUserBudgetIsGone() {
        givenMemory(0, null);
        when(messageRepository.countBySessionId(sessionId)).thenReturn(16L);
        when(messageRepository.fetchSlice(sessionId, 0, 6)).thenReturn(rows(1, 6));
        when(tokenBudgetService.snapshot(userId)).thenReturn(new TokenBudgetService.Snapshot(100, 100, 1.0,
                TokenBudgetService.Status.EXHAUSTED, Instant.now().plusSeconds(3600), "memory"));

        service.summarizeNow(sessionId, userId, runId);

        verifyNoInteractions(providerRouter);
        verify(memoryRepository, never()).recordFailure(any(), anyString(), any());
    }

    @Test
    void doesNothingUntilAWholeChunkIsReady() {
        givenMemory(0, null);
        when(messageRepository.countBySessionId(sessionId)).thenReturn(13L);   // 可折叠 5 条 < chunk 6

        service.summarizeNow(sessionId, userId, runId);

        verifyNoInteractions(providerRouter);
        verify(memoryRepository, never()).advance(any(), anyInt(), anyInt(), anyString(), anyInt(), anyString(),
                anyInt(), any());
    }

    @Test
    void respectsTheConcurrentWinnerAndStillPaysForTheLostCall() {
        givenMemory(0, null);
        when(messageRepository.countBySessionId(sessionId)).thenReturn(16L);
        when(messageRepository.fetchSlice(sessionId, 0, 6)).thenReturn(rows(1, 6));
        when(tokenBudgetService.snapshot(userId)).thenReturn(normalBudget());
        when(providerRouter.chat(any(), any(), any(), anyBoolean()))
                .thenReturn(new ProviderRouter.ChatOutcome(new ChatResponse("摘要正文", "mock", 10, 5), "mock",
                        List.of(), List.of()));
        when(memoryRepository.advance(eq(sessionId), eq(0), eq(6), anyString(), anyInt(), anyString(), anyInt(), any()))
                .thenReturn(0);   // 另一个实例先赢了

        service.summarizeNow(sessionId, userId, runId);

        verify(tokenBudgetService).charge(userId, 15);
        verify(eventBus, never()).publish(any());
    }

    @Test
    void selfHealsWhenTheWatermarkRunsAheadOfTheMessages() {
        // 账本说「已折 20 条」，但可折叠上限只有 12（消息被删过）—— 必须先把账本扳回来
        givenMemory(20, "旧摘要");
        when(messageRepository.countBySessionId(sessionId)).thenReturn(20L);
        when(messageRepository.fetchSlice(sessionId, 0, 6)).thenReturn(List.of());

        service.summarizeNow(sessionId, userId, runId);

        verify(memoryRepository).reset(eq(sessionId), any());
        verifyNoInteractions(providerRouter);
    }

    @Test
    void previewCountsHistoryByAssemblyShapeNotByContent() {
        // 连着说两句一样的话：上一轮那条不能被当成「本轮输入」而漏数（真实栈控制台手验撞到过）
        SessionMemoryPort echo = new SessionMemoryPort() {
            @Override
            public List<ChatMessage> history(UUID session, int limit) {
                return List.of(ChatMessage.user("继续"), ChatMessage.assistant("好"), ChatMessage.user("继续"));
            }

            @Override
            public Optional<String> summary(UUID session) {
                return Optional.empty();
            }
        };
        SessionMemoryService withEcho = new SessionMemoryService(memoryRepository, messageRepository,
                sessionRepository, providerRouter, tokenBudgetService,
                new SessionContextProvider(echo, PROPERTIES), echo, maintenance, PROPERTIES, eventBus);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(mock(SessionEntity.class)));

        SessionMemoryService.MemoryPreview preview = withEcho.preview(sessionId, "继续");

        assertThat(preview.messages()).as("3 条历史 + 本轮输入").hasSize(4);
        assertThat(preview.historyCount()).isEqualTo(3);
        assertThat(preview.summaryIncluded()).isFalse();
    }

    @Test
    void previewDoesNotClassifyCurrentUserTextThatStartsWithAContextLabel() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(mock(SessionEntity.class)));
        List<String> labels = List.of(SessionContextProvider.SUMMARY_LABEL,
                SessionContextProvider.LONG_MEMORY_LABEL, SessionContextProvider.PROFILE_PREFERENCE_LABEL);

        for (String label : labels) {
            SessionMemoryService.MemoryPreview preview = service.preview(sessionId, label + "用户正文");

            assertThat(preview.profileIncluded()).as(label).isFalse();
            assertThat(preview.summaryIncluded()).as(label).isFalse();
            assertThat(preview.longMemoryIncluded()).as(label).isFalse();
            assertThat(preview.historyCount()).as(label).isZero();
            assertThat(preview.messages()).as(label).hasSize(1);
        }
    }

    @Test
    void previewCountsUserAndAssistantHistoryEvenWhenTheirTextStartsWithAContextLabel() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(mock(SessionEntity.class)));
        List<String> labels = List.of(SessionContextProvider.SUMMARY_LABEL,
                SessionContextProvider.LONG_MEMORY_LABEL, SessionContextProvider.PROFILE_PREFERENCE_LABEL);

        for (String label : labels) {
            SessionMemoryPort labeledHistory = new SessionMemoryPort() {
                @Override
                public List<ChatMessage> history(UUID session, int limit) {
                    return List.of(ChatMessage.user(label + "历史用户正文"),
                            ChatMessage.assistant(label + "历史助手正文"));
                }

                @Override
                public Optional<String> summary(UUID session) {
                    return Optional.empty();
                }
            };
            SessionMemoryService withHistory = new SessionMemoryService(memoryRepository, messageRepository,
                    sessionRepository, providerRouter, tokenBudgetService,
                    new SessionContextProvider(labeledHistory, PROPERTIES), labeledHistory, maintenance,
                    PROPERTIES, eventBus);

            SessionMemoryService.MemoryPreview preview = withHistory.preview(sessionId, "本轮输入");

            assertThat(preview.profileIncluded()).as(label).isFalse();
            assertThat(preview.summaryIncluded()).as(label).isFalse();
            assertThat(preview.longMemoryIncluded()).as(label).isFalse();
            assertThat(preview.historyCount()).as(label).isEqualTo(2);
            assertThat(preview.messages()).as(label).hasSize(3);
        }
    }

    private void givenMemory(int summarizedCount, String summary) {
        givenMemory(0, summarizedCount, summary);
    }

    private void givenMemory(int attempts, int summarizedCount, String summary) {
        SessionMemoryEntity entity = mock(SessionMemoryEntity.class);
        when(entity.getSummarizedCount()).thenReturn(summarizedCount);
        when(entity.getSummary()).thenReturn(summary);
        when(entity.getAttempts()).thenReturn(attempts);
        when(memoryRepository.findById(sessionId)).thenReturn(Optional.of(entity));
    }

    private static TokenBudgetService.Snapshot normalBudget() {
        return new TokenBudgetService.Snapshot(10, 100_000, 0.0, TokenBudgetService.Status.NORMAL,
                Instant.now().plusSeconds(3600), "memory");
    }

    private static List<MessageEntity> rows(int from, int to) {
        Instant base = Instant.now();
        java.util.ArrayList<MessageEntity> rows = new java.util.ArrayList<>();
        for (int index = from; index <= to; index++) {
            rows.add(new MessageEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    index % 2 == 1 ? "USER" : "ASSISTANT", "TEXT", "第" + index + "句", null, null,
                    base.plusMillis(index)));
        }
        return rows;
    }
}
