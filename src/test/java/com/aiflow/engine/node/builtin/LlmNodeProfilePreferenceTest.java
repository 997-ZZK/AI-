package com.aiflow.engine.node.builtin;

import com.aiflow.engine.attachment.AttachmentStore;
import com.aiflow.engine.budget.InMemoryTokenUsageStore;
import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.config.AttachmentProperties;
import com.aiflow.engine.config.BudgetProperties;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.config.SafetyProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.SessionContextProvider;
import com.aiflow.engine.memory.SessionMemoryPort;
import com.aiflow.engine.memory.longterm.LongMemoryProperties;
import com.aiflow.engine.memory.longterm.LongMemoryRecallPort;
import com.aiflow.engine.memory.longterm.MemoryKind;
import com.aiflow.engine.memory.longterm.MemorySource;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.profile.ProfilePreferencePort;
import com.aiflow.engine.profile.ProfilePreferenceResolver;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 捕获真实节点交给 provider 的消息及选档，不以“没有报错”代替接线验证。 */
class LlmNodeProfilePreferenceTest {

    private final UUID userId = UUID.randomUUID();
    private final BudgetProperties budgetProperties = new BudgetProperties(true, 1000, 0.8, "UTC", "额度用尽", "memory");
    private final TokenBudgetService budget = new TokenBudgetService(new InMemoryTokenUsageStore(), budgetProperties);
    private final AtomicInteger preferenceReads = new AtomicInteger();

    @Test
    void onePreferenceReadControlsBothTheInjectedStyleAndCheapFirstRouting() {
        Capturing cloud = new Capturing("cloud", Set.of(Capabilities.CHAT));
        Capturing local = new Capturing("local", Set.of(Capabilities.CHAT));

        NodeResult result = chat(cloud, local).execute(context("llm.chat", Map.of()));

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(result.output()).containsEntry("provider", "local");
        assertThat(cloud.request).isNull();
        assertThat(local.request.messages().getFirst().content())
                .startsWith(SessionContextProvider.PROFILE_PREFERENCE_LABEL).contains("回复简洁");
        assertThat(local.request.messages().getLast().content()).isEqualTo("本轮合成输入");
        assertThat(preferenceReads).hasValue(1);
    }

    @Test
    void explicitModelRetainsConfiguredRoutingAndTheStylePreference() {
        Capturing cloud = new Capturing("cloud", Set.of(Capabilities.CHAT));
        Capturing local = new Capturing("local", Set.of(Capabilities.CHAT));

        NodeResult result = chat(cloud, local).execute(context("llm.chat", Map.of("model", "fixed-model")));

        assertThat(result.output()).containsEntry("provider", "cloud");
        assertThat(local.request).isNull();
        assertThat(cloud.request.model()).isEqualTo("fixed-model");
        assertThat(cloud.request.messages().getFirst().content()).startsWith(SessionContextProvider.PROFILE_PREFERENCE_LABEL);
        assertThat(preferenceReads).hasValue(1);
    }

    @Test
    void workflowNoneStopsProfileApplication() {
        Capturing cloud = new Capturing("cloud", Set.of(Capabilities.CHAT));
        Capturing local = new Capturing("local", Set.of(Capabilities.CHAT));

        NodeResult result = chat(cloud, local).execute(context("llm.chat", Map.of("profilePreference", "none")));

        assertThat(result.output()).containsEntry("provider", "cloud");
        assertThat(cloud.request.messages()).extracting(ChatMessage::content).containsExactly("本轮合成输入");
        assertThat(local.request).isNull();
    }

    @Test
    void softBudgetStillPrioritizesCheapTiersWithAnExplicitModelAndWorkflowNone() {
        Capturing cloud = new Capturing("cloud", Set.of(Capabilities.CHAT));
        Capturing local = new Capturing("local", Set.of(Capabilities.CHAT));
        budget.charge(userId, 800);

        NodeResult result = chat(cloud, local).execute(context("llm.chat",
                Map.of("model", "fixed-model", "profilePreference", "none")));

        assertThat(result.output()).containsEntry("provider", "local");
        assertThat(local.request.model()).isEqualTo("fixed-model");
        assertThat(local.request.messages()).extracting(ChatMessage::content).containsExactly("本轮合成输入");
        assertThat(cloud.request).isNull();
    }

    @Test
    void hardBudgetPreventsBothProfileReadsAndProviderCalls() {
        Capturing cloud = new Capturing("cloud", Set.of(Capabilities.CHAT));
        Capturing local = new Capturing("local", Set.of(Capabilities.CHAT));
        budget.charge(userId, 1000);

        NodeResult result = chat(cloud, local).execute(context("llm.chat", Map.of()));

        assertThat(result.status()).isEqualTo(NodeRunStatus.DEGRADED);
        assertThat(cloud.request).isNull();
        assertThat(local.request).isNull();
        assertThat(preferenceReads).hasValue(0);
    }

    @Test
    void visionUsesTheSameAssemblyAndKeepsCapabilityFiltering() {
        Capturing cloud = new Capturing("cloud", Set.of(Capabilities.CHAT, Capabilities.VISION));
        Capturing local = new Capturing("local", Set.of(Capabilities.CHAT));
        VisionNodeExecutor vision = new VisionNodeExecutor(router(cloud, local), mock(AttachmentStore.class),
                AttachmentProperties.defaults(), PermissiveSafety.create(), SafetyProperties.defaults(), budget,
                budgetProperties, stream(), memory());

        NodeResult result = vision.execute(context("llm.vision", Map.of("images", "https://example.com/synthetic.png")));

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(result.output()).containsEntry("provider", "cloud");
        assertThat(local.request).isNull();
        assertThat(cloud.request.messages().getFirst().content()).startsWith(SessionContextProvider.PROFILE_PREFERENCE_LABEL);
        assertThat(cloud.request.messages().getLast().images()).hasSize(1);
        assertThat(preferenceReads).hasValue(1);
    }

    private LlmNodeExecutor chat(Capturing... providers) {
        return new LlmNodeExecutor(router(providers), PermissiveSafety.create(), SafetyProperties.defaults(), budget,
                budgetProperties, stream(), memory());
    }

    private ProviderRouter router(Capturing... providers) {
        ProviderProperties defaults = ProviderProperties.defaults();
        ProviderProperties properties = new ProviderProperties("auto", new String[]{"cloud", "local", "mock"},
                List.of("local", "mock"), defaults.mock(), defaults.endpoints(), defaults.probe(), defaults.breaker());
        return new ProviderRouter(List.of(providers), properties);
    }

    private SessionContextProvider memory() {
        ProfilePreferenceResolver resolver = new ProfilePreferenceResolver(id -> {
            preferenceReads.incrementAndGet();
            return new ProfilePreferencePort.Snapshot(true, List.of(
                    new ProfilePreferencePort.Entry(UUID.randomUUID(), MemoryKind.PREFERENCE, MemorySource.MANUAL,
                            "response-style", "concise"),
                    new ProfilePreferencePort.Entry(UUID.randomUUID(), MemoryKind.PREFERENCE, MemorySource.MANUAL,
                            "model-policy", "cheap-first")));
        });
        return new SessionContextProvider(SessionMemoryPort.disabled(), MemoryProperties.defaults(),
                LongMemoryRecallPort.disabled(), LongMemoryProperties.defaults(), resolver);
    }

    private StreamingSafetyGuard stream() {
        return new StreamingSafetyGuard(PermissiveSafety.create(), SafetyProperties.defaults());
    }

    private NodeContext context(String type, Map<String, Object> params) {
        NodeDef node = new NodeDef("model", type, "合成节点", params, Set.of(), null, null);
        return new NodeContext(UUID.randomUUID(), UUID.randomUUID(), userId, node,
                new HashMap<>(Map.of("input", "本轮合成输入")), new InMemoryRunEventBus(EngineProperties.defaults()), () -> false);
    }

    private static final class Capturing implements ChatProvider {
        private final String id;
        private final Set<String> capabilities;
        private ChatRequest request;

        private Capturing(String id, Set<String> capabilities) {
            this.id = id;
            this.capabilities = capabilities;
        }

        @Override public String id() { return id; }
        @Override public Set<String> capabilities() { return capabilities; }

        @Override
        public ChatResponse chat(ChatRequest request, Consumer<String> sink) {
            this.request = request;
            sink.accept("合成回答");
            return new ChatResponse("合成回答", id, 2, 2);
        }
    }
}
