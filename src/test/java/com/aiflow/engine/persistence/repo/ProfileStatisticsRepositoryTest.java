package com.aiflow.engine.persistence.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileStatisticsRepositoryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void invalidAndMissingTokensRemainUnknown() throws Exception {
        for (String json : new String[]{"{}", "{\"tokensUsed\":null}", "{\"tokensUsed\":-1}",
                "{\"tokensUsed\":1.5}", "{\"tokensUsed\":\"12\"}", "{\"tokensUsed\":9223372036854775808}"}) {
            assertThat(ProfileStatisticsRepository.node("llm.chat", Instant.EPOCH, mapper.readTree(json)).tokens())
                    .as(json).isNull();
        }
        assertThat(ProfileStatisticsRepository.node("llm.chat", Instant.EPOCH, null).tokens()).isNull();
    }

    @Test
    void recordedZeroAndUnknownProviderAreSeparateFacts() throws Exception {
        var knownZero = ProfileStatisticsRepository.node("llm.chat", Instant.EPOCH,
                mapper.readTree("{\"tokensUsed\":0,\"provider\":\"mock\",\"reply\":\"unrelated\"}"));
        assertThat(knownZero.tokens()).isZero();
        assertThat(knownZero.provider()).isEqualTo("mock");
        var unknown = ProfileStatisticsRepository.node("llm.vision", Instant.EPOCH,
                mapper.readTree("{\"tokensUsed\":12,\"provider\":7}"));
        assertThat(unknown.tokens()).isEqualTo(12);
        assertThat(unknown.provider()).isNull();
    }
}
