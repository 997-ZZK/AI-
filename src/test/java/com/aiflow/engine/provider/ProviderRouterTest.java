package com.aiflow.engine.provider;

import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.run.RunLeaseLostException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRouterTest {

    @Test
    void followsConfiguredOrder() {
        ProviderProperties properties = properties("auto", "b", "a");
        ProviderRouter router = new ProviderRouter(List.of(fake("a"), fake("b")), properties);

        assertThat(router.route(Set.of(Capabilities.CHAT)).id()).isEqualTo("b");
    }

    @Test
    void mockModeFiltersRealProviders() {
        ProviderProperties properties = properties("mock", "a", "mock");
        ProviderRouter router = new ProviderRouter(List.of(fake("a"), fake("mock")), properties);

        assertThat(router.route(Set.of(Capabilities.CHAT)).id()).isEqualTo("mock");
    }

    @Test
    void throwsWhenNoProviderSatisfiesCapability() {
        ProviderProperties properties = ProviderProperties.defaults();
        ProviderRouter router = new ProviderRouter(List.of(fake("mock")), properties);

        assertThatThrownBy(() -> router.route(Set.of(Capabilities.VISION)))
                .isInstanceOf(CapabilityUnavailableException.class)
                .hasMessageContaining("vision");
    }

    @Test
    void leaseLossFromStreamingCallbackDoesNotTryAnotherProvider() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        ChatProvider streaming = new ChatProvider() {
            @Override
            public String id() { return "first"; }

            @Override
            public Set<String> capabilities() { return Set.of(Capabilities.CHAT); }

            @Override
            public ChatResponse chat(ChatRequest request, Consumer<String> sink) {
                sink.accept("piece");
                return new ChatResponse("piece", "first", 0, 0);
            }
        };
        ChatProvider fallback = new ChatProvider() {
            @Override
            public String id() { return "next"; }

            @Override
            public Set<String> capabilities() { return Set.of(Capabilities.CHAT); }

            @Override
            public ChatResponse chat(ChatRequest request, Consumer<String> sink) {
                fallbackCalls.incrementAndGet();
                return new ChatResponse("fallback", "next", 0, 0);
            }
        };
        ProviderRouter router = new ProviderRouter(List.of(streaming, fallback), properties("auto", "first", "next"));
        assertThatThrownBy(() -> router.chat(Set.of(Capabilities.CHAT), new ChatRequest(null, List.of()),
                piece -> { throw new RunLeaseLostException("lease lost"); }))
                .isInstanceOf(RunLeaseLostException.class);
        assertThat(fallbackCalls).hasValue(0);
    }

    private static ProviderProperties properties(String mode, String... order) {
        ProviderProperties defaults = ProviderProperties.defaults();
        return new ProviderProperties(mode, order, defaults.cheapTiers(), defaults.mock(), defaults.endpoints(), defaults.probe(),
                defaults.breaker());
    }

    private ChatProvider fake(String id) {        return new ChatProvider() {
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
                return new ChatResponse("ok", id, 0, 0);
            }
        };
    }
}
