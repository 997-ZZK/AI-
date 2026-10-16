package com.aiflow.engine.node.builtin;

import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.retrieval.ContextPromptAssembler;
import com.aiflow.engine.retrieval.CosineScoreNormalizer;
import com.aiflow.engine.retrieval.RagRetrievalService;
import com.aiflow.engine.retrieval.RetrievalException;
import com.aiflow.engine.retrieval.RetrievalProvider;
import com.aiflow.engine.retrieval.RetrievalQuery;
import com.aiflow.engine.retrieval.RetrievedChunk;
import com.aiflow.engine.retrieval.TestChunks;
import com.aiflow.engine.retrieval.mock.MockEmbeddingProvider;
import com.aiflow.engine.retrieval.post.RetrievalParams;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalNodeExecutorTest {

    private static final String PRIVATE_MARKER = "synthetic-retrieval-provider-private-body";
    private final EngineProperties engineProperties = EngineProperties.defaults();
    private final MockEmbeddingProvider embeddings = new MockEmbeddingProvider("mock-embedding", 32);

    @Test
    void rendersQueryTemplateAndPublishesContext() {
        AtomicReference<String> seenQuery = new AtomicReference<>();
        RetrievalProvider provider = stub(query -> {
            seenQuery.set(query.text());
            return List.of(TestChunks.chunk("doc-a", 0, "发票抬头必须为公司全称", 0.9));
        });
        NodeContext context = newContext(Map.of("kbId", "kb-1"), new HashMap<>(Map.of("input", "发票抬头要写什么")));

        NodeResult result = executor(provider).execute(context);

        assertThat(result.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(seenQuery.get()).isEqualTo("发票抬头要写什么");
        assertThat(String.valueOf(result.output().get("context"))).contains("[1]").contains("<<<资料1>>>");
        assertThat(context.variables()).containsKeys("context", "chunks", "citations");
        assertThat(String.valueOf(result.output().get("sources"))).contains("local");
    }

    @Test
    void failsClearlyWhenQueryIsEmpty() {
        NodeContext context = newContext(Map.of(), new HashMap<>());

        NodeResult result = executor(stub(query -> List.of())).execute(context);

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error()).contains("检索查询为空");
    }

    @Test
    void failsWhenEverySourceIsUnavailable() {
        RetrievalProvider broken = stub(query -> {
            throw new RetrievalException("知识库不可用");
        });
        NodeContext context = newContext(Map.of("kbId", "kb-1"), new HashMap<>(Map.of("input", "任何问题")));

        NodeResult result = executor(broken).execute(context);

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error()).contains("所有检索来源都不可用");
    }

    @Test
    void doesNotExposeProviderExceptionInNodeFailure() {
        RetrievalProvider broken = stub(query -> {
            throw new IllegalStateException(PRIVATE_MARKER);
        });
        NodeContext context = newContext(Map.of("kbId", "kb-1"), new HashMap<>(Map.of("input", "任何问题")));

        NodeResult result = executor(broken).execute(context);

        assertThat(result.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(result.error()).doesNotContain(PRIVATE_MARKER).isEqualTo("所有检索来源都不可用: local: 来源检索失败");
    }

    @Test
    void declaresTypeAndCapabilities() {
        RetrievalNodeExecutor executor = executor(stub(query -> List.of()));
        assertThat(executor.type()).isEqualTo("retrieval.search");
        assertThat(executor.requiredCapabilities()).containsExactly(Capabilities.EMBEDDING);
    }

    private RetrievalNodeExecutor executor(RetrievalProvider provider) {
        RagRetrievalService service = new RagRetrievalService(embeddings, List.of(provider),
                List.of(new CosineScoreNormalizer("local")), RetrievalParams.defaults(), new ContextPromptAssembler());
        return new RetrievalNodeExecutor(service, RetrievalProperties.defaults());
    }

    private NodeContext newContext(Map<String, Object> params, Map<String, Object> variables) {
        NodeDef node = new NodeDef("retrieve", "retrieval.search", "检索", params, Set.of(), null, null);
        return new NodeContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), node, variables,
                new InMemoryRunEventBus(engineProperties), () -> false);
    }

    private static RetrievalProvider stub(java.util.function.Function<RetrievalQuery, List<RetrievedChunk>> behavior) {
        return new RetrievalProvider() {
            @Override
            public String source() {
                return "local";
            }

            @Override
            public List<RetrievedChunk> retrieve(RetrievalQuery query) {
                return behavior.apply(query);
            }
        };
    }
}
