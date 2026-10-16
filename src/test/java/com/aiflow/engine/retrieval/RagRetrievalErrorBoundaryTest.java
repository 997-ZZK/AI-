package com.aiflow.engine.retrieval;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.aiflow.engine.retrieval.mock.MockEmbeddingProvider;
import com.aiflow.engine.retrieval.post.RetrievalParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagRetrievalErrorBoundaryTest {

    private static final String MARKER = "synthetic-retrieval-query-key-and-upstream-body-marker";

    private final Logger logger = (Logger) LoggerFactory.getLogger(RagRetrievalService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final MockEmbeddingProvider embeddings = new MockEmbeddingProvider("mock-embedding", 64);

    @BeforeEach
    void startLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stopLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void sanitizesEmbeddingFailureAndDropsExceptionChain() {
        EmbeddingProvider broken = new EmbeddingProvider() {
            @Override
            public String id() {
                return "synthetic-embedding";
            }

            @Override
            public int dimensions() {
                return 64;
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                throw sensitiveFailure();
            }
        };

        RetrievalException failure = assertThrows(RetrievalException.class,
                () -> service(broken, List.of(stub("local", query -> List.of())))
                        .retrieve(MARKER, "kb-1", Set.of(), null, null));

        assertThat(failure).hasMessage("embedding 调用失败");
        assertSanitized(failure);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void sanitizesEmbeddingDimensionsFailure() {
        EmbeddingProvider broken = new EmbeddingProvider() {
            @Override
            public String id() {
                return "synthetic-embedding";
            }

            @Override
            public int dimensions() {
                throw sensitiveFailure();
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                return List.of(new float[64]);
            }
        };

        RetrievalException failure = assertThrows(RetrievalException.class,
                () -> service(broken, List.of(stub("local", query -> List.of())))
                        .retrieve("问题", "kb-1", Set.of(), null, null));

        assertThat(failure).hasMessage("embedding 配置失败");
        assertSanitized(failure);
    }

    @Test
    void keepsHealthySourceWorkingWhenAnotherHealthCheckThrows() {
        RetrievalProvider unhealthy = new RetrievalProvider() {
            @Override
            public String source() {
                return "cloud";
            }

            @Override
            public boolean healthy() {
                throw sensitiveFailure();
            }

            @Override
            public List<RetrievedChunk> retrieve(RetrievalQuery query) {
                throw new AssertionError("health check should stop this source");
            }
        };
        RetrievalProvider local = stub("local", query ->
                List.of(TestChunks.chunk("local", "doc-a", 0, "本地内容", 0.8, null)));

        RetrievalResult result = service(embeddings, List.of(unhealthy, local))
                .retrieve("问题", "kb-1", Set.of(), null, null);

        assertThat(result.sources()).containsExactly("local");
        assertWarningsSanitized(1);
    }

    @Test
    void sanitizesProviderFailureInLogsAndFinalFailure() {
        RetrievalProvider broken = stub("cloud", query -> {
            throw sensitiveFailure();
        });

        RetrievalException failure = assertThrows(RetrievalException.class,
                () -> service(embeddings, List.of(broken))
                        .retrieve("问题", "kb-1", Set.of(), null, null));

        assertThat(failure).hasMessage("所有检索来源都不可用: cloud: 来源检索失败");
        assertSanitized(failure);
        assertWarningsSanitized(1);
    }

    @Test
    void preservesPartialDegradationWithoutLeakingBrokenSourceDetails() {
        RetrievalProvider broken = stub("cloud", query -> {
            throw sensitiveFailure();
        });
        RetrievalProvider local = stub("local", query ->
                List.of(TestChunks.chunk("local", "doc-a", 0, "本地内容", 0.8, null)));

        RetrievalResult result = service(embeddings, List.of(local, broken))
                .retrieve("问题", "kb-1", Set.of(), null, null);

        assertThat(result.sources()).containsExactly("local");
        assertThat(result.chunks()).hasSize(1);
        assertWarningsSanitized(1);
    }

    private RagRetrievalService service(EmbeddingProvider embedding, List<RetrievalProvider> providers) {
        return new RagRetrievalService(embedding, providers,
                List.of(new CosineScoreNormalizer("local"), new LinearRangeScoreNormalizer("cloud", 0, 100)),
                RetrievalParams.defaults(), new ContextPromptAssembler());
    }

    private static RetrievalProvider stub(String source, Function<RetrievalQuery, List<RetrievedChunk>> behavior) {
        return new RetrievalProvider() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public List<RetrievedChunk> retrieve(RetrievalQuery query) {
                return behavior.apply(query);
            }
        };
    }

    private static SensitiveFailure sensitiveFailure() {
        SensitiveFailure failure = new SensitiveFailure();
        failure.addSuppressed(new IllegalStateException(MARKER));
        return failure;
    }

    private static void assertSanitized(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(MARKER);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private void assertWarningsSanitized(int expectedCount) {
        assertThat(logs.list).hasSize(expectedCount).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).doesNotContain(MARKER);
            if (event.getThrowableProxy() != null) {
                assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(MARKER);
            }
        });
    }

    private static final class SensitiveFailure extends RuntimeException {
        private SensitiveFailure() {
            super(MARKER);
        }

        @Override
        public String toString() {
            return MARKER;
        }
    }
}
