package com.aiflow.engine.integration;

import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.retrieval.RetrievalQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.aiflow.engine.integration.CloudKnowledgeBaseException.Reason.INVALID_RESPONSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonKnowledgeBaseProtocolTest {
    private final JsonKnowledgeBaseProtocol protocol = new JsonKnowledgeBaseProtocol(RetrievalProperties.CloudMapping.defaults());

    @Test
    void mapsCustomNestedEnvelopeAndFieldNamesWithoutChangingEngineDto() throws Exception {
        RetrievalProperties.CloudMapping mapping = new RetrievalProperties.CloudMapping("index", "question", "limit",
                "/payload/hits", "/hit", "/document/id", "/document/name", "/text", "/rank/score", "/order",
                "/heading", "/startPage", "/endPage", "/hash");
        JsonKnowledgeBaseProtocol custom = new JsonKnowledgeBaseProtocol(mapping);
        byte[] request = custom.encode(new RetrievalQuery("synthetic question", null, "kb", 7, 0, Map.of()), "index-1");
        assertThat(new ObjectMapper().readTree(request)).isEqualTo(new ObjectMapper().readTree("""
                {"index":"index-1","question":"synthetic question","limit":7}
                """));
        var result = custom.decode("custom", bytes("""
                {"payload":{"hits":[{"hit":"c1","document":{"id":"d1","name":"Guide"},"text":"sample",
                "rank":{"score":-3.5},"order":9,"heading":"Section","startPage":1,"endPage":2,"hash":"abc"}]}}
                """));
        assertThat(result.getFirst().source()).isEqualTo("custom");
        assertThat(result.getFirst().rawScore()).isEqualTo(-3.5);
        assertThat(result.getFirst().chunkIndex()).isEqualTo(9);
        assertThat(result.getFirst().contentHash()).isEqualTo("abc");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"chunks\":null}", "{\"chunks\":[null]}",
            "{\"chunks\":[{\"id\":\"c\",\"document_id\":\"d\",\"content\":\"sample\"}]}",
            "{\"chunks\":[{\"id\":\"c\",\"document_id\":\"d\",\"content\":\"sample\",\"score\":null}]}",
            "{\"chunks\":[{\"id\":\"c\",\"document_id\":\"d\",\"content\":\"sample\",\"score\":\"82\"}]}",
            "{\"chunks\":[{\"id\":\"c\",\"document_id\":\"d\",\"content\":\"sample\",\"score\":1e999}]}",
            "{\"chunks\":[{\"id\":\"c\",\"document_id\":\"d\",\"content\":\"sample\",\"score\":1,\"chunk_index\":-1}]}",
            "{\"chunks\":[{\"id\":\"c\",\"document_id\":\"d\",\"content\":\"sample\",\"score\":1,\"chunk_index\":1.5}]}",
            "{\"chunks\":[{\"id\":\"c\",\"document_id\":\"d\",\"content\":\"sample\",\"score\":1,\"page_from\":3,\"page_to\":2}]}",
            "{\"chunks\":[{\"id\":\"c\",\"document_id\":\"d\",\"content\":\"sample\",\"score\":1,\"page_from\":0}]}",
            "{\"chunks\":[{\"id\":\"\",\"document_id\":\"d\",\"content\":\"sample\",\"score\":1}]}",
            "vendor-private-invalid-json"
    })
    void rejectsMissingScoreNonFiniteScoreAndMalformedHitsWithNoRawCause(String response) {
        assertThatThrownBy(() -> protocol.decode("source", bytes(response)))
                .isInstanceOfSatisfying(CloudKnowledgeBaseException.class, error -> {
                    assertThat(error.reason()).isEqualTo(INVALID_RESPONSE);
                    assertThat(error.getMessage()).doesNotContain("vendor-private", "sample", response);
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void retainsZeroScoreButDoesNotInventDocumentOrder() {
        var hits = protocol.decode("source", bytes("""
                {"chunks":[{"id":"c","document_id":"d","content":"sample","score":0}]}
                """));
        assertThat(hits.getFirst().rawScore()).isZero();
        assertThat(hits.getFirst().chunkIndex()).isEqualTo(-1);
        assertThat(hits.getFirst().docTitle()).isEqualTo("d");
    }

    @Test
    void bindsAdditiveHttpSettingsAndDefaultsWithoutReadingEnvironmentValues() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "aiflow.retrieval.cloud.enabled", "true",
                "aiflow.retrieval.cloud.http.api-key-env", "SYNTHETIC_KB_TOKEN",
                "aiflow.retrieval.cloud.http.retry-safe", "true",
                "aiflow.retrieval.cloud.http.max-attempts", "2",
                "aiflow.retrieval.cloud.http.mapping.chunks-pointer", "/data/hits")));
        RetrievalProperties properties = binder.bind("aiflow.retrieval", Bindable.of(RetrievalProperties.class)).get();
        assertThat(properties.cloud().http().apiKeyEnv()).isEqualTo("SYNTHETIC_KB_TOKEN");
        assertThat(properties.cloud().http().retrievePath()).isEqualTo("/retrieve");
        assertThat(properties.cloud().http().maxAttempts()).isEqualTo(2);
        assertThat(properties.cloud().http().retrySafe()).isTrue();
        assertThat(properties.cloud().http().maxConcurrentCalls()).isEqualTo(4);
        assertThat(properties.cloud().http().mapping().chunksPointer()).isEqualTo("/data/hits");
        assertThat(properties.cloud().http().mapping().scorePointer()).isEqualTo("/score");
    }

    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
