package com.aiflow.engine.node.builtin;

import com.aiflow.engine.budget.InMemoryTokenUsageStore;
import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.config.BudgetProperties;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.config.SafetyProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.memory.HistoryOptions;
import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.SessionContextProvider;
import com.aiflow.engine.memory.SessionMemoryPort;
import com.aiflow.engine.memory.longterm.LongMemoryOptions;
import com.aiflow.engine.memory.longterm.LongMemoryProperties;
import com.aiflow.engine.memory.longterm.LongMemoryRecallPort;
import com.aiflow.engine.memory.longterm.MemoryKind;
import com.aiflow.engine.memory.longterm.RecalledMemory;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatProvider;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.safety.PermissiveSafety;
import com.aiflow.engine.safety.StreamingSafetyGuard;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「长期记忆真的发给了模型」的接线用例：断言的是**捕获到的请求消息列表**，不是「没报错」。
 *
 * <p>两类记忆各有一块带标签的 system，顺序固定（长期记忆在前，摘要次之）—— 模型必须能分辨
 * 「用户长期如此」与「他刚才这么说过」；这两块的措辞与顺序在这里钉住。
 */
class LlmNodeLongMemoryInjectionTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void putsLongMemoryBeforeSummaryWindowAndCurrentInput() {
        Capturing provider = new Capturing();
        SessionContextProvider memory = memory(new FakePort()
                .history(ChatMessage.user("上一句"), ChatMessage.assistant("上一句的回答")).summary("在聊报销"),
                (id, query, topK, writeBack) -> List.of(
                        new RecalledMemory(UUID.randomUUID(), MemoryKind.FACT, "公司报销规则", "报销要附行程单", 0.9),
                        new RecalledMemory(UUID.randomUUID(), MemoryKind.PREFERENCE, "回答长度", "三行以内", 0.8)));

        NodeResult result = executor(provider, memory).execute(context(Map.of("input", "那要附什么"), Map.of()));

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        List<ChatMessage> messages = provider.last.messages();
        assertThat(messages).hasSize(5);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content())
                .startsWith(SessionContextProvider.LONG_MEMORY_LABEL)
                .contains("[FACT] 公司报销规则：报销要附行程单")
                .contains("[PREFERENCE] 回答长度：三行以内");
        assertThat(messages.get(1).content())
                .as("摘要紧随其后且**另有标签**").startsWith(SessionContextProvider.SUMMARY_LABEL).contains("在聊报销");
        assertThat(messages.get(2).content()).isEqualTo("上一句");
        assertThat(messages.get(3).content()).isEqualTo("上一句的回答");
        assertThat(messages.get(4).content()).isEqualTo("那要附什么");
    }

    @Test
    void nodeParameterCanTurnLongMemoryOff() {
        Capturing provider = new Capturing();
        SessionContextProvider memory = memory(new FakePort().summary("在聊报销"),
                (id, query, topK, writeBack) -> List.of(new RecalledMemory(UUID.randomUUID(), MemoryKind.FACT, "k", "v", 0.9)));

        executor(provider, memory).execute(context(Map.of("input", "翻译这段话"), Map.of("longMemory", "none")));

        assertThat(provider.last.messages()).extracting(ChatMessage::content)
                .as("长期记忆关掉，摘要（短期）不受影响").noneMatch(content -> content.startsWith(SessionContextProvider.LONG_MEMORY_LABEL));
    }

    @Test
    void recallFailureNeverBreaksTheRun() {
        Capturing provider = new Capturing();
        SessionContextProvider memory = memory(new FakePort(), (id, query, topK, writeBack) -> {
            throw new IllegalStateException("向量服务不可用");
        });

        NodeResult result = executor(provider, memory).execute(context(Map.of("input", "照常回答"), Map.of()));

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(provider.last.messages()).extracting(ChatMessage::content).containsExactly("照常回答");
    }

    @Test
    void recallSwitchOffMeansNoRecallCallAtAll() {
        AtomicInteger calls = new AtomicInteger();
        SessionContextProvider memory = new SessionContextProvider(new FakePort(),
                new MemoryProperties(true, 8, 4000, true, 6, 8000, 1200, true, 5),
                (id, query, topK, writeBack) -> {
                    calls.incrementAndGet();
                    return List.of();
                },
                // recall-enabled=false：连一次 embedding 都不该发生（这是「召回是花钱的那一步」的开关）
                new LongMemoryProperties(true, false, true, 8, 6, Duration.ofMinutes(3), 5, 0.6, 200, 200, 3, -1, 800,
                        200));

        List<ChatMessage> messages = memory.assemble(UUID.randomUUID(), userId, ChatMessage.user("问题"),
                HistoryOptions.inherit(8), LongMemoryOptions.inherit());

        assertThat(calls.get()).isZero();
        assertThat(messages).extracting(ChatMessage::content).containsExactly("问题");
    }

    @Test
    void previewAssemblyReadsTheSameChainButDoesNotCountAsUsage() {
        List<Boolean> writeBackSeen = new ArrayList<>();
        SessionContextProvider memory = memory(new FakePort(),
                (id, query, topK, writeBack) -> {
                    writeBackSeen.add(writeBack);
                    return List.of(new RecalledMemory(UUID.randomUUID(), MemoryKind.FACT, "公司报销规则", "报销要附行程单", 0.9));
                });

        // 预览（装配口径与真实运行一致，唯独不记账）
        memory.assemble(UUID.randomUUID(), userId, ChatMessage.user("问题"), HistoryOptions.inherit(8),
                LongMemoryOptions.inherit(), false);
        // 真实装配
        memory.assemble(UUID.randomUUID(), userId, ChatMessage.user("问题"), HistoryOptions.inherit(8),
                LongMemoryOptions.inherit());

        assertThat(writeBackSeen).as("预览不记账、真实装配记账").containsExactly(false, true);
    }

    private LlmNodeExecutor executor(ChatProvider provider, SessionContextProvider memory) {
        ProviderProperties defaults = ProviderProperties.defaults();
        ProviderProperties properties = new ProviderProperties("auto", new String[]{"cloud", "local", "mock"},
                List.of("local", "mock"), defaults.mock(), defaults.endpoints(), defaults.probe(),
                defaults.breaker());
        ProviderRouter router = new ProviderRouter(List.of(provider), properties);
        BudgetProperties budgetProperties = new BudgetProperties(true, 100_000, 0.8, "Asia/Shanghai", "额度已用尽",
                "memory");
        TokenBudgetService budget = new TokenBudgetService(new InMemoryTokenUsageStore(), budgetProperties);
        return new LlmNodeExecutor(router, PermissiveSafety.create(), SafetyProperties.defaults(), budget,
                budgetProperties, new StreamingSafetyGuard(PermissiveSafety.create(), SafetyProperties.defaults()),
                memory);
    }

    private NodeContext context(Map<String, Object> variables, Map<String, Object> params) {
        Map<String, Object> nodeParams = new HashMap<>(Map.of("prompt", "${input}"));
        nodeParams.putAll(params);
        NodeDef node = new NodeDef("llm", "llm.chat", "对话", nodeParams, Set.of("chat"), null, null);
        return new NodeContext(UUID.randomUUID(), UUID.randomUUID(), userId, node, new HashMap<>(variables),
                new InMemoryRunEventBus(EngineProperties.defaults()), () -> false);
    }

    private static SessionContextProvider memory(SessionMemoryPort port, LongMemoryRecallPort recallPort) {
        return new SessionContextProvider(port,
                new MemoryProperties(true, 8, 4000, true, 6, 8000, 1200, true, 5), recallPort,
                new LongMemoryProperties(true, true, true, 8, 6, Duration.ofMinutes(3), 5, 0.6, 200, 200, 3, -1, 800,
                        200));
    }

    private static final class FakePort implements SessionMemoryPort {

        private List<ChatMessage> history = List.of();
        private String summary;

        FakePort history(ChatMessage... messages) {
            this.history = List.of(messages);
            return this;
        }

        FakePort summary(String text) {
            this.summary = text;
            return this;
        }

        @Override
        public List<ChatMessage> history(UUID sessionId, int limit) {
            return history;
        }

        @Override
        public Optional<String> summary(UUID sessionId) {
            return Optional.ofNullable(summary);
        }
    }

    private static final class Capturing implements ChatProvider {

        private ChatRequest last;

        @Override
        public String id() {
            return "capturing";
        }

        @Override
        public Set<String> capabilities() {
            return Set.of(Capabilities.CHAT);
        }

        @Override
        public ChatResponse chat(ChatRequest request, Consumer<String> deltaConsumer) {
            this.last = request;
            deltaConsumer.accept("收到");
            return new ChatResponse("收到", "capturing", 3, 1);
        }
    }
}
