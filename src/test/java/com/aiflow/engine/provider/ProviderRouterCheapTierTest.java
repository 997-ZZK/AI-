package com.aiflow.engine.provider;

import com.aiflow.engine.config.ProviderProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「优先便宜档位」的路由用例：额度接近上限时换档，但不改变健康检查与失败换档的规则。
 *
 * <p>这里的 provider id 恰好等于档位名（cloud / local / mock），与真实配置一致；
 * 档位名来自端点配置时走的是同一套 {@code tierOf} 解析（见 ProviderTierRoutingIntegrationTest）。
 */
class ProviderRouterCheapTierTest {

    @Test
    void prefersCheapTierOnlyWhenAsked() {
        Stub cloud = new Stub("cloud");
        ProviderRouter router = router(List.of(cloud, new Stub("local"), new Stub("mock")));

        assertThat(router.chat(Set.of(Capabilities.CHAT), request(), chunk -> { }).providerId())
                .as("额度正常时按配置顺序，先用云端加速").isEqualTo("cloud");
        assertThat(router.chat(Set.of(Capabilities.CHAT), request(), chunk -> { }, true).providerId())
                .as("进入软阈值后改走便宜档位：降级而不是中断").isEqualTo("local");
    }

    @Test
    void keepsConfiguredOrderInsideTheCheapGroup() {
        List<String> used = new ArrayList<>();
        ProviderRouter router = router(List.of(new Stub("cloud"), new Stub("local"), new Stub("mock")));

        ProviderRouter.ChatOutcome outcome = router.chat(Set.of(Capabilities.CHAT), request(), used::add, true);

        assertThat(outcome.providerId()).isEqualTo("local");
        assertThat(used).containsExactly("local answered");
    }

    @Test
    void stillFallsBackToTheExpensiveTierWhenCheapOnesAreBroken() {
        ProviderRouter router = router(List.of(new Stub("cloud"), new Stub("local").failing(), new Stub("mock").failing()));

        ProviderRouter.ChatOutcome outcome = router.chat(Set.of(Capabilities.CHAT), request(), chunk -> { }, true);

        assertThat(outcome.providerId())
                .as("便宜档位全挂时不能报「无可用档位」：降级是换顺序，不是自断后路")
                .isEqualTo("cloud");
        assertThat(outcome.failedAttempts()).extracting(AllProvidersFailedException.FailedAttempt::providerId)
                .containsExactly("local", "mock");
    }

    @Test
    void ignoresTheHintWhenNoCheapTierIsConfigured() {
        ProviderProperties defaults = ProviderProperties.defaults();
        ProviderProperties properties = new ProviderProperties("auto", new String[]{"cloud", "local", "mock"},
                List.of(), defaults.mock(), defaults.endpoints(), defaults.probe(), defaults.breaker());
        ProviderRouter router = new ProviderRouter(List.of(new Stub("cloud"), new Stub("local")), properties);

        assertThat(router.chat(Set.of(Capabilities.CHAT), request(), chunk -> { }, true).providerId())
                .isEqualTo("cloud");
    }

    private static ProviderRouter router(List<ChatProvider> providers) {
        ProviderProperties defaults = ProviderProperties.defaults();
        ProviderProperties properties = new ProviderProperties("auto", new String[]{"cloud", "local", "mock"},
                List.of("local", "mock"), defaults.mock(), defaults.endpoints(), defaults.probe(),
                defaults.breaker());
        return new ProviderRouter(providers, properties);
    }

    private static ChatRequest request() {
        return new ChatRequest(null, List.of(ChatMessage.user("你好")));
    }

    private static final class Stub implements ChatProvider {

        private final String id;
        private final AtomicInteger calls = new AtomicInteger();
        private boolean failing;

        private Stub(String id) {
            this.id = id;
        }

        private Stub failing() {
            this.failing = true;
            return this;
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
            if (failing) {
                throw new ProviderCallException(id, id + " is down");
            }
            String reply = calls.get() == 1 ? id + " answered" : id + " answered again";
            deltaConsumer.accept(reply);
            return new ChatResponse(reply, id, 1, 1);
        }
    }
}