package com.aiflow.engine.provider;

import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.node.RunCanceledException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分级路由的降级语义。这些是「兜底」能不能信的关键：该换档的换、不该换档的绝不换。
 */
class ProviderRouterFailoverTest {

    @Test
    void fallsBackToNextTierWhenFirstFails() {
        List<String> calls = new ArrayList<>();
        ChatProvider cloud = provider("cloud", true, true, delta -> {
            calls.add("cloud");
            throw new ProviderCallException("cloud", "连接超时");
        });
        ChatProvider local = provider("local", true, true, delta -> {
            calls.add("local");
            delta.accept("本地兜底回答");
            return new ChatResponse("本地兜底回答", "qwen2.5", 3, 4);
        });
        ProviderRouter router = router(cloud, local, "cloud", "local");

        ProviderRouter.ChatOutcome outcome = router.chat(Set.of(Capabilities.CHAT),
                new ChatRequest(null, List.of(ChatMessage.user("你好"))), chunk -> { });

        assertThat(calls).containsExactly("cloud", "local");
        assertThat(outcome.providerId()).isEqualTo("local");
        assertThat(outcome.response().content()).isEqualTo("本地兜底回答");
        assertThat(outcome.failedAttempts()).hasSize(1);
        assertThat(outcome.failedAttempts().get(0).providerId()).isEqualTo("cloud");
    }

    @Test
    void doesNotSwitchTierAfterDeltaAlreadyDelivered() {
        List<String> calls = new ArrayList<>();
        ChatProvider cloud = provider("cloud", true, true, delta -> {
            calls.add("cloud");
            delta.accept("前半句");
            throw new ProviderCallException("cloud", "流中断");
        });
        ChatProvider local = provider("local", true, true, delta -> {
            calls.add("local");
            return new ChatResponse("本地兜底回答", "qwen2.5", 3, 4);
        });
        ProviderRouter router = router(cloud, local, "cloud", "local");
        List<String> delivered = new ArrayList<>();

        assertThatThrownBy(() -> router.chat(Set.of(Capabilities.CHAT),
                new ChatRequest(null, List.of(ChatMessage.user("你好"))), delivered::add))
                .isInstanceOf(AllProvidersFailedException.class)
                .satisfies(error -> assertThat(((AllProvidersFailedException) error).isPartialOutputDelivered())
                        .isTrue());

        assertThat(calls).as("已经吐过字就不能换档，否则两段话会拼在一起").containsExactly("cloud");
        assertThat(delivered).containsExactly("前半句");
    }

    @Test
    void cancellationDoesNotTriggerFailover() {
        ChatProvider cloud = provider("cloud", true, true, delta -> {
            throw new RunCanceledException();
        });
        AtomicBoolean localCalled = new AtomicBoolean(false);
        ChatProvider local = provider("local", true, true, delta -> {
            localCalled.set(true);
            return new ChatResponse("x", "x", 1, 1);
        });
        ProviderRouter router = router(cloud, local, "cloud", "local");

        assertThatThrownBy(() -> router.chat(Set.of(Capabilities.CHAT),
                new ChatRequest(null, List.of(ChatMessage.user("你好"))), chunk -> { }))
                .isInstanceOf(RunCanceledException.class);
        assertThat(localCalled).isFalse();
    }

    @Test
    void recordsTiersSkippedBecauseProbeMarkedThemDown() {
        ChatProvider cloud = provider("cloud", false, true, delta -> new ChatResponse("云端", "m", 1, 1));
        ChatProvider local = provider("local", true, true, delta -> new ChatResponse("本地", "m", 1, 1));
        ProviderRouter router = router(cloud, local, "cloud", "local");

        ProviderRouter.ChatOutcome outcome = router.chat(Set.of(Capabilities.CHAT),
                new ChatRequest(null, List.of(ChatMessage.user("你好"))), chunk -> { });

        assertThat(outcome.providerId()).isEqualTo("local");
        assertThat(outcome.skippedProviders()).as("探活判死的档位没被尝试，但必须留名").containsExactly("cloud");
        assertThat(outcome.failedAttempts()).isEmpty();
    }

    @Test
    void prefersHealthyTierOverHigherPriorityBrokenOne() {
        ChatProvider cloud = provider("cloud", false, true, delta -> new ChatResponse("云端", "m", 1, 1));
        ChatProvider local = provider("local", true, true, delta -> new ChatResponse("本地", "m", 1, 1));
        ProviderRouter router = router(cloud, local, "cloud", "local");

        assertThat(router.route(Set.of(Capabilities.CHAT)).id()).isEqualTo("local");
    }

    @Test
    void stillTriesWhenEveryTierIsUnhealthy() {
        List<String> calls = new ArrayList<>();
        ChatProvider cloud = provider("cloud", false, true, delta -> {
            calls.add("cloud");
            throw new ProviderCallException("cloud", "还是连不上");
        });
        ChatProvider local = provider("local", false, true, delta -> {
            calls.add("local");
            return new ChatResponse("本地", "m", 1, 1);
        });
        ProviderRouter router = router(cloud, local, "cloud", "local");

        ProviderRouter.ChatOutcome outcome = router.chat(Set.of(Capabilities.CHAT),
                new ChatRequest(null, List.of(ChatMessage.user("你好"))), chunk -> { });

        assertThat(calls).containsExactly("cloud", "local");
        assertThat(outcome.providerId()).isEqualTo("local");
    }

    @Test
    void reportsEveryTierFailureWhenAllFail() {
        ChatProvider cloud = provider("cloud", true, true, delta -> {
            throw new ProviderCallException("cloud", "云端 502");
        });
        ChatProvider local = provider("local", true, true, delta -> {
            throw new ProviderCallException("local", "本地模型未拉取");
        });
        ProviderRouter router = router(cloud, local, "cloud", "local");

        assertThatThrownBy(() -> router.chat(Set.of(Capabilities.CHAT),
                new ChatRequest(null, List.of(ChatMessage.user("你好"))), chunk -> { }))
                .isInstanceOf(AllProvidersFailedException.class)
                .hasMessageContaining("云端 502")
                .hasMessageContaining("本地模型未拉取");
    }

    private static ProviderRouter router(ChatProvider first, ChatProvider second, String... order) {
        ProviderProperties defaults = ProviderProperties.defaults();
        ProviderProperties properties = new ProviderProperties("auto", order, defaults.cheapTiers(), defaults.mock(), defaults.endpoints(),
                defaults.probe(), defaults.breaker());
        return new ProviderRouter(List.of(first, second), properties);
    }

    private static ChatProvider provider(String id, boolean healthy, boolean useCapabilities,
                                         java.util.function.Function<Consumer<String>, ChatResponse> behavior) {
        return new ChatProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> capabilities() {
                return useCapabilities ? Set.of(Capabilities.CHAT) : Set.of();
            }

            @Override
            public boolean healthy() {
                return healthy;
            }

            @Override
            public ChatResponse chat(ChatRequest request, Consumer<String> deltaConsumer) {
                return behavior.apply(deltaConsumer);
            }
        };
    }
}