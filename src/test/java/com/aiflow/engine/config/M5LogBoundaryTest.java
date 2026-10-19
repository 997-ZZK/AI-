package com.aiflow.engine.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aiflow.engine.integration.HttpEndpointFactory;
import com.aiflow.engine.integration.OpenAiCompatibleChatProvider;
import com.aiflow.engine.provider.EndpointHealth;
import com.aiflow.engine.retrieval.TieredEmbeddingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** M5 provider 启动诊断不得把 endpoint 地址或其中的凭证带进日志。 */
class M5LogBoundaryTest {

    private static final String PRIVATE_MARKER = "m5-private-token-marker";
    private static final String PRIVATE_BASE_URL =
            "http://" + PRIVATE_MARKER + "@127.0.0.1:19091/v1?token=" + PRIVATE_MARKER;

    @Test
    void endpointFactoryStartupLogKeepsOnlyControlledMetadata() {
        Logger logger = logger(HttpEndpointFactory.class);
        ListAppender<ILoggingEvent> appender = attach(logger);
        try {
            ProviderProperties defaults = ProviderProperties.defaults();
            ProviderProperties.Endpoint endpoint = new ProviderProperties.Endpoint(PRIVATE_BASE_URL,
                    "fixture-model", "fixture-key", Set.of("chat"), true, 1_000, "local", "", 0);
            ProviderProperties properties = new ProviderProperties("auto", new String[]{"local"}, List.of("local"),
                    defaults.mock(), Map.of("local", endpoint),
                    new ProviderProperties.Probe(false, 60_000, 1_000), defaults.breaker());

            new HttpEndpointFactory(properties, new ObjectMapper());

            String output = messages(appender);
            assertThat(output).contains("provider endpoint local 已装配 model=fixture-model tier=local");
            assertThat(output).doesNotContain(PRIVATE_MARKER, "fixture-key", "base-url", "http://");

            OpenAiCompatibleChatProvider provider = new OpenAiCompatibleChatProvider(
                    "local", endpoint, new EndpointHealth("local", true, 3, 1_000), new ObjectMapper());
            assertThat(provider.description()).isEqualTo("fixture-model").doesNotContain(PRIVATE_MARKER, "http://");
        } finally {
            detach(logger, appender);
        }
    }

    @Test
    void embeddingChainStartupLogDoesNotPrintTierAddress() {
        Logger logger = logger(RetrievalConfig.class);
        ListAppender<ILoggingEvent> appender = attach(logger);
        try {
            ProviderProperties defaults = ProviderProperties.defaults();
            ProviderProperties properties = new ProviderProperties("auto", new String[]{"local"}, List.of("local"),
                    defaults.mock(), Map.of(),
                    new ProviderProperties.Probe(false, 60_000, 1_000), defaults.breaker());
            HttpEndpointFactory endpoints = new HttpEndpointFactory(properties, new ObjectMapper());
            RetrievalProperties.Embedding embedding = new RetrievalProperties.Embedding("http", -1, 4,
                    "fixture-embedding", PRIVATE_BASE_URL, "fixture-key", 1_000,
                    List.of(new RetrievalProperties.EmbeddingTier("local", PRIVATE_BASE_URL,
                            "fixture-embedding", "fixture-key", true, 1_000)));

            TieredEmbeddingProvider chain = RetrievalConfig.buildEmbeddingChain(embedding, endpoints);

            String output = messages(appender);
            assertThat(chain.tiers()).singleElement();
            assertThat(output).contains("embedding 链条（按优先级）：[local[local] http-embedding:fixture-embedding model=fixture-embedding]");
            assertThat(output).doesNotContain(PRIVATE_MARKER, "fixture-key", "http://");
        } finally {
            detach(logger, appender);
        }
    }

    private static Logger logger(Class<?> type) {
        return (Logger) LoggerFactory.getLogger(type);
    }

    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static String messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (all, next) -> all + next);
    }
}
