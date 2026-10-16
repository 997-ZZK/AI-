package com.aiflow.engine.node.builtin;

import com.aiflow.engine.budget.InMemoryTokenUsageStore;
import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.config.BudgetProperties;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.config.SafetyProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.SessionContextProvider;
import com.aiflow.engine.memory.SessionMemoryPort;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「历史真的发给了模型」的接线用例：断言的是**捕获到的请求消息列表**，不是"没报错"。
 *
 * <p>节点里最容易悄悄退化的地方就是这里：装配器写对了、节点却仍旧 `List.of(user(prompt))`，
 * 光看接口测试永远是绿的。
 */
class LlmNodeHistoryInjectionTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void sendsSummaryAndWindowHistoryBeforeTheCurrentInput() {
        Capturing provider = new Capturing();
        SessionContextProvider memory = memory(new FakePort()
                .history(ChatMessage.user("上一句"), ChatMessage.assistant("上一句的回答"))
                .summary("用户在做离线部署"));

        NodeResult result = executor(provider, memory).execute(context(Map.of("input", "本轮输入"), Map.of()));

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(provider.last.messages()).hasSize(4);
        assertThat(provider.last.messages().get(0).role()).isEqualTo("system");
        assertThat(provider.last.messages().get(0).content()).contains("用户在做离线部署");
        assertThat(provider.last.messages().subList(1, 3)).extracting(ChatMessage::content)
                .containsExactly("上一句", "上一句的回答");
        assertThat(provider.last.messages().get(3).content()).isEqualTo("本轮输入");
    }

    @Test
    void nodeParameterCanTurnHistoryOffForPureFunctions() {
        Capturing provider = new Capturing();
        SessionContextProvider memory = memory(new FakePort().history(ChatMessage.user("不该出现")));

        executor(provider, memory).execute(context(Map.of("input", "翻译这段话"),
                Map.of("history", "none")));

        assertThat(provider.last.messages()).extracting(ChatMessage::content).containsExactly("翻译这段话");
    }

    @Test
    void historyFailureNeverBreaksTheRun() {
        Capturing provider = new Capturing();
        SessionContextProvider memory = memory(new FakePort().explode());

        NodeResult result = executor(provider, memory).execute(context(Map.of("input", "照常回答"), Map.of()));

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(provider.last.messages()).extracting(ChatMessage::content).containsExactly("照常回答");
    }

    private LlmNodeExecutor executor(ChatProvider provider, SessionContextProvider memory) {
        ProviderProperties defaults = ProviderProperties.defaults();
        ProviderProperties properties = new ProviderProperties("auto", new String[]{"cloud", "local", "mock"},
                List.of("local", "mock"), defaults.mock(), defaults.endpoints(), defaults.probe(),
                defaults.breaker());
        ProviderRouter router = new ProviderRouter(List.of(provider), properties);
        TokenBudgetService budget = new TokenBudgetService(new InMemoryTokenUsageStore(),
                new BudgetProperties(true, 100_000, 0.8, "Asia/Shanghai", "额度已用尽", "memory"));
        return new LlmNodeExecutor(router, PermissiveSafety.create(), SafetyProperties.defaults(), budget,
                new BudgetProperties(true, 100_000, 0.8, "Asia/Shanghai", "额度已用尽", "memory"),
                new StreamingSafetyGuard(PermissiveSafety.create(), SafetyProperties.defaults()), memory);
    }

    private NodeContext context(Map<String, Object> variables, Map<String, Object> params) {
        Map<String, Object> nodeParams = new HashMap<>(Map.of("prompt", "${input}"));
        nodeParams.putAll(params);
        NodeDef node = new NodeDef("llm", "llm.chat", "对话", nodeParams, Set.of("chat"), null, null);
        return new NodeContext(UUID.randomUUID(), UUID.randomUUID(), userId, node, new HashMap<>(variables),
                new InMemoryRunEventBus(EngineProperties.defaults()), () -> false);
    }

    private static SessionContextProvider memory(SessionMemoryPort port) {
        return new SessionContextProvider(port, new MemoryProperties(true, 8, 4000, true, 6, 8000, 1200, true, 5));
    }

    private static final class FakePort implements SessionMemoryPort {

        private List<ChatMessage> history = List.of();
        private String summary;
        private boolean explode;

        FakePort history(ChatMessage... messages) {
            this.history = List.of(messages);
            return this;
        }

        FakePort summary(String text) {
            this.summary = text;
            return this;
        }

        FakePort explode() {
            this.explode = true;
            return this;
        }

        @Override
        public List<ChatMessage> history(UUID sessionId, int limit) {
            if (explode) {
                throw new IllegalStateException("会话记忆不可用");
            }
            return history;
        }

        @Override
        public Optional<String> summary(UUID sessionId) {
            if (explode) {
                throw new IllegalStateException("会话记忆不可用");
            }
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
