package com.aiflow.engine.node.builtin;

import com.aiflow.engine.attachment.AttachmentStore;
import com.aiflow.engine.budget.PermissiveBudget;
import com.aiflow.engine.config.AttachmentProperties;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class VisionNodeExecutorTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path tempDir;

    @Test
    void resolvesAttachmentIntoInlineImageAndRecordsDegradationTrace() {
        AttachmentStore store = store(4);
        UUID attachmentId = store.store("cat.png", PNG).id();
        AtomicReference<ChatRequest> seen = new AtomicReference<>();
        ProviderRouter router = router(new StubProvider("cloud-vl",
                Set.of(Capabilities.CHAT, Capabilities.VISION), seen));
        Map<String, Object> variables = new HashMap<>(Map.of(
                "input", "图里有什么", "attachments", attachmentId.toString()));

        NodeResult result = executor(router, store).execute(newContext(Map.of(), variables));

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(seen.get().messages()).hasSize(1);
        assertThat(seen.get().messages().get(0).content()).isEqualTo("图里有什么");
        assertThat(seen.get().messages().get(0).images()).hasSize(1);
        assertThat(seen.get().messages().get(0).images().get(0).url()).startsWith("data:image/png;base64,");
        assertThat(result.output()).containsEntry("provider", "cloud-vl").containsEntry("imageCount", 1);
        assertThat(result.output().get("imageRefs")).isEqualTo(List.of(attachmentId.toString()));
        assertThat(variables.get("reply")).isEqualTo("cloud-vl 的描述");
    }

    @Test
    void acceptsHttpLinkWithoutTouchingTheStore() {
        AtomicReference<ChatRequest> seen = new AtomicReference<>();
        ProviderRouter router = router(new StubProvider("cloud-vl",
                Set.of(Capabilities.CHAT, Capabilities.VISION), seen));
        Map<String, Object> variables = new HashMap<>(Map.of(
                "input", "这是什么", "attachments", "https://cdn.example.com/a.png"));

        NodeResult result = executor(router, store(4)).execute(newContext(Map.of(), variables));

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(seen.get().messages().get(0).images().get(0).url())
                .isEqualTo("https://cdn.example.com/a.png");
    }

    @Test
    void failsClearlyWhenNoImageIsReferenced() {
        NodeResult result = executor(router(new StubProvider("cloud-vl",
                Set.of(Capabilities.CHAT, Capabilities.VISION), new AtomicReference<>())), store(4))
                .execute(newContext(Map.of(), new HashMap<>(Map.of("input", "图里有什么"))));

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error()).contains("没有可用的图片引用");
    }

    @Test
    void failsWhenReferenceIsNeitherAttachmentNorLink() {
        Map<String, Object> variables = new HashMap<>(Map.of("input", "图里有什么", "attachments", "not-a-uuid"));

        NodeResult result = executor(router(new StubProvider("cloud-vl",
                Set.of(Capabilities.CHAT, Capabilities.VISION), new AtomicReference<>())), store(4))
                .execute(newContext(Map.of(), variables));

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error()).contains("无法识别的图片引用");
    }

    @Test
    void excludesProvidersWithoutVisionCapability() {
        AttachmentStore store = store(4);
        String attachmentId = store.store("cat.png", PNG).id().toString();
        NodeResult result = executor(router(new StubProvider("text-only",
                        Set.of(Capabilities.CHAT), new AtomicReference<>())), store)
                .execute(newContext(Map.of(), new HashMap<>(Map.of(
                        "input", "图里有什么", "attachments", attachmentId))));

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error())
                .as("纯文本档位必须被排除，并给出可操作的提示，而不是一句看不懂的调用失败")
                .contains("vision")
                .contains("capabilities: [chat, vision]");
    }

    @Test
    void reportsMissingVisionTierBeforeTouchingAttachments() {
        NodeResult result = executor(router(new StubProvider("text-only",
                        Set.of(Capabilities.CHAT), new AtomicReference<>())), store(4))
                .execute(newContext(Map.of(), new HashMap<>(Map.of(
                        "input", "图里有什么", "attachments", UUID.randomUUID().toString()))));

        assertThat(result.error())
                .as("没有 vision 档位时要先说这件事：附件不存在只是次要信息，顺序颠倒会把人带偏")
                .contains("vision")
                .doesNotContain("附件不存在");
    }

    @Test
    void rejectsMoreImagesThanConfiguredLimit() {
        Map<String, Object> variables = new HashMap<>(Map.of("input", "看看",
                "attachments", UUID.randomUUID() + "," + UUID.randomUUID() + "," + UUID.randomUUID()
                        + "," + UUID.randomUUID() + "," + UUID.randomUUID()));

        NodeResult result = executor(router(new StubProvider("cloud-vl",
                Set.of(Capabilities.CHAT, Capabilities.VISION), new AtomicReference<>())), store(4))
                .execute(newContext(Map.of(), variables));

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error()).contains("最多处理 4 张");
    }

    @Test
    void declaresTypeAndCapabilities() {
        VisionNodeExecutor executor = executor(router(new StubProvider("cloud-vl",
                Set.of(Capabilities.CHAT, Capabilities.VISION), new AtomicReference<>())), store(4));

        assertThat(executor.type()).isEqualTo("llm.vision");
        assertThat(executor.requiredCapabilities())
                .as("只声明 chat 的档位必须被排除：把图交给纯文本模型等于让它对着 base64 猜")
                .containsExactlyInAnyOrder(Capabilities.CHAT, Capabilities.VISION);
    }

    private VisionNodeExecutor executor(ProviderRouter router, AttachmentStore store) {
        return new VisionNodeExecutor(router, store, new AttachmentProperties(tempDir.toString(), 1024 * 1024, 4),
                PermissiveSafety.create(), SafetyProperties.defaults(),
                PermissiveBudget.service(), PermissiveBudget.properties(),
                new StreamingSafetyGuard(PermissiveSafety.create(), SafetyProperties.defaults()));
    }

    private AttachmentStore store(int maxPerRequest) {
        return new AttachmentStore(new AttachmentProperties(tempDir.toString(), 1024 * 1024, maxPerRequest),
                new ObjectMapper());
    }

    private ProviderRouter router(ChatProvider provider) {
        return new ProviderRouter(List.of(provider), ProviderProperties.defaults());
    }

    private NodeContext newContext(Map<String, Object> params, Map<String, Object> variables) {
        NodeDef node = new NodeDef("vision", "llm.vision", "看图", params, Set.of(), null, null);
        return new NodeContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), node, variables,
                new InMemoryRunEventBus(EngineProperties.defaults()), () -> false);
    }

    private static final class StubProvider implements ChatProvider {

        private final String id;
        private final Set<String> capabilities;
        private final AtomicReference<ChatRequest> seen;

        private StubProvider(String id, Set<String> capabilities, AtomicReference<ChatRequest> seen) {
            this.id = id;
            this.capabilities = capabilities;
            this.seen = seen;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<String> capabilities() {
            return capabilities;
        }

        @Override
        public ChatResponse chat(ChatRequest request, Consumer<String> deltaConsumer) {
            seen.set(request);
            String reply = id + " 的描述";
            deltaConsumer.accept(reply);
            return new ChatResponse(reply, id, 3, 5);
        }
    }
}