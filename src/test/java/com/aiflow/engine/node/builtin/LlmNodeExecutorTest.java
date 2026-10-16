package com.aiflow.engine.node.builtin;

import com.aiflow.engine.budget.InMemoryTokenUsageStore;
import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.config.BudgetProperties;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.config.SafetyProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.provider.Capabilities;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话节点的额度用例：三件事各自独立、互不代替。
 *
 * <p>① 额度用尽时不调用模型（拦在源头，不是答完再道歉）；② 正常返回后按真实用量扣减并写进输出；
 * ③ 进入软阈值时把「优先便宜档位」这件事真的传给了路由 —— 只写进日志等于没做。
 */
class LlmNodeExecutorTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void blocksBeforeCallingTheModelWhenTheBudgetIsGone() {
        Stub cloud = new Stub("cloud");
        TokenBudgetService budget = budget(10, 0.8);
        budget.charge(userId, 10);

        NodeResult result = executor(router(cloud), budget).execute(context(new HashMap<>(Map.of("input", "你好"))));

        assertThat(result.status()).isEqualTo(NodeRunStatus.DEGRADED);
        assertThat(result.output()).containsEntry("budgetBlocked", true)
                .containsEntry("reply", "额度已用尽")
                .containsEntry("budgetStatus", "EXHAUSTED");
        assertThat(result.error()).contains("额度已用尽");
        assertThat(cloud.calls.get()).as("额度用尽时一次模型调用都不该发生").isZero();
    }

    @Test
    void chargesTheReportedUsageAndPublishesBudgetState() {
        Stub cloud = new Stub("cloud");
        TokenBudgetService budget = budget(100, 0.8);

        NodeResult result = executor(router(cloud), budget).execute(context(new HashMap<>(Map.of("input", "你好"))));

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(result.output()).containsEntry("tokensUsed", 12L)
                .containsEntry("budgetUsed", 12L)
                .containsEntry("budgetLimit", 100L)
                .containsEntry("budgetStatus", "NORMAL");
        assertThat(budget.snapshot(userId).used()).as("扣减口径 = prompt + completion").isEqualTo(12);
    }

    @Test
    void asksTheRouterForACheapTierOnceTheSoftThresholdIsReached() {
        Stub cloud = new Stub("cloud");
        Stub mock = new Stub("mock");
        TokenBudgetService budget = budget(100, 0.8);

        assertThat(executor(router(cloud, mock), budget)
                .execute(context(new HashMap<>(Map.of("input", "第一轮")))).output())
                .as("额度正常时用云端").containsEntry("provider", "cloud");

        budget.charge(userId, 80);

        NodeResult soft = executor(router(cloud, mock), budget)
                .execute(context(new HashMap<>(Map.of("input", "第二轮"))));

        assertThat(soft.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(soft.output()).containsEntry("provider", "mock").containsEntry("budgetStatus", "EXHAUSTED");
        assertThat(cloud.calls.get()).as("软阈值后不该再打云端").isEqualTo(1);
    }

    private TokenBudgetService budget(long limit, double softRatio) {
        return new TokenBudgetService(new InMemoryTokenUsageStore(),
                new BudgetProperties(true, limit, softRatio, "Asia/Shanghai", "额度已用尽", "memory"));
    }

    private LlmNodeExecutor executor(ProviderRouter router, TokenBudgetService budget) {
        BudgetProperties properties = new BudgetProperties(true, 10_000, 0.8, "Asia/Shanghai", "额度已用尽", "memory");
        return new LlmNodeExecutor(router, PermissiveSafety.create(), SafetyProperties.defaults(), budget, properties,
                new StreamingSafetyGuard(PermissiveSafety.create(), SafetyProperties.defaults()));
    }

    private NodeContext context(Map<String, Object> variables) {
        NodeDef node = new NodeDef("llm", "llm.chat", "对话", Map.of("prompt", "${input}"), Set.of("chat"), null, null);
        return new NodeContext(UUID.randomUUID(), UUID.randomUUID(), userId, node, variables,
                new InMemoryRunEventBus(EngineProperties.defaults()), () -> false);
    }

    private static ProviderRouter router(ChatProvider... providers) {
        ProviderProperties defaults = ProviderProperties.defaults();
        ProviderProperties properties = new ProviderProperties("auto", new String[]{"cloud", "local", "mock"},
                List.of("local", "mock"), defaults.mock(), defaults.endpoints(), defaults.probe(),
                defaults.breaker());
        return new ProviderRouter(List.of(providers), properties);
    }

    private static final class Stub implements ChatProvider {

        private final String id;
        private final AtomicInteger calls = new AtomicInteger();

        private Stub(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<String> capabilities() {
            return Set.of(Capabilities.CHAT);
        }

        @Override
        public ChatResponse chat(ChatRequest request, Consumer<String> deltaConsumer) {
            calls.incrementAndGet();
            String reply = id + " 的回答";
            deltaConsumer.accept(reply);
            // 报 7 + 5 token：让「扣减用的是 provider 报的数」这件事可断言
            return new ChatResponse(reply, id, 7, 5);
        }
    }
}