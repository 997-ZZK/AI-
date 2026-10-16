package com.aiflow.engine.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class EndpointExtraBodyTest {

    private static final String PRIVATE_MARKER = "synthetic-extra-body-private-marker";
    private static final String INVALID_OBJECT = "extra-body 不是合法 JSON 对象";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void blankMeansNoExtraFields() {
        assertThat(EndpointExtraBody.parse("", objectMapper)).isEmpty();
        assertThat(EndpointExtraBody.parse(null, objectMapper)).isEmpty();
        assertThat(EndpointExtraBody.parse(" \t\r\n", objectMapper)).isEmpty();
    }

    @Test
    void parsesVendorFields() {
        Map<String, Object> parsed = EndpointExtraBody.parse("{\"thinking\":{\"type\":\"disabled\"}}", objectMapper);

        assertThat(parsed).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void preservesVendorValueTypesAndNestedNulls() {
        Map<String, Object> parsed = EndpointExtraBody.parse("""
                {"enable_thinking":false,"temperature":0.25,"limit":12,
                 "options":{"nullable":null},"stops":["END",null]}
                """, objectMapper);

        assertThat(parsed).containsEntry("enable_thinking", false)
                .containsEntry("temperature", 0.25)
                .containsEntry("limit", 12)
                .containsEntry("options", Collections.singletonMap("nullable", null))
                .containsEntry("stops", Arrays.asList("END", null));
    }

    @Test
    void rejectsMalformedJsonWithoutLeakingPrivateFieldsOrValues() {
        String json = "{\"" + PRIVATE_MARKER + "\":\"" + PRIVATE_MARKER + "\",}";

        assertSafeFailure(() -> EndpointExtraBody.parse(json, objectMapper), INVALID_OBJECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "false", "42", "\"synthetic-extra-body-private-marker\"",
            "[\"synthetic-extra-body-private-marker\"]"})
    void rejectsEveryNonObjectRoot(String json) {
        assertSafeFailure(() -> EndpointExtraBody.parse(json, objectMapper), INVALID_OBJECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{} {}", "{} []", "{} null", "{} true",
            "{} \"synthetic-extra-body-private-marker\"", "{} synthetic-extra-body-private-marker"})
    void rejectsTrailingJsonOrGarbage(String json) {
        assertSafeFailure(() -> EndpointExtraBody.parse(json, objectMapper), INVALID_OBJECT);
    }

    @Test
    void acceptsOneObjectWithTrailingWhitespace() {
        assertThat(EndpointExtraBody.parse("{\"enable_thinking\":false} \t\r\n", objectMapper))
                .containsExactlyEntriesOf(Map.of("enable_thinking", false));
        assertThat(EndpointExtraBody.parse("{} \t\r\n", objectMapper)).isEmpty();
    }

    @Test
    void rejectsTopLevelNullWithAFixedDiagnostic() {
        String json = "{\"" + PRIVATE_MARKER + "\":null}";

        assertSafeFailure(() -> EndpointExtraBody.parse(json, objectMapper),
                "extra-body 顶层字段不能为 null");
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "messages", "stream"})
    void rejectsProtocolBackboneOverrideWithoutLeakingItsValue(String reserved) {
        String json = "{\"" + reserved + "\":\"" + PRIVATE_MARKER + "\"}";

        assertSafeFailure(() -> EndpointExtraBody.parse(json, objectMapper),
                "extra-body 不能覆盖 " + reserved + "：协议主干字段由引擎管理");
    }

    @Test
    void strictTrailingValidationDoesNotChangeTheSharedMapper() throws Exception {
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();

        assertSafeFailure(() -> EndpointExtraBody.parse("{} {}", objectMapper), INVALID_OBJECT);

        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
        assertThat(objectMapper.readTree("{} {}").isObject()).isTrue();
    }

    @Test
    void returnsAnUnmodifiableMap() {
        Map<String, Object> parsed = EndpointExtraBody.parse("{\"enable_thinking\":false}", objectMapper);

        assertThatThrownBy(() -> parsed.put("temperature", 0.5))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> EndpointExtraBody.parse("", objectMapper).put("temperature", 0.5))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void convertsMapperRuntimeFailureWithoutRetainingItsExceptionGraph() {
        IllegalStateException privateFailure = new IllegalStateException(PRIVATE_MARKER,
                new IllegalArgumentException(PRIVATE_MARKER));
        privateFailure.addSuppressed(new RuntimeException(PRIVATE_MARKER));
        ObjectMapper failingMapper = mock(ObjectMapper.class, invocation -> {
            throw privateFailure;
        });

        assertSafeFailure(() -> EndpointExtraBody.parse("{}", failingMapper), INVALID_OBJECT);
    }

    private static void assertSafeFailure(Runnable action, String expectedMessage) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertThat(failure).hasMessage(expectedMessage).hasNoCause();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage()).doesNotContain(PRIVATE_MARKER);
        assertThat(failure.toString()).doesNotContain(PRIVATE_MARKER);
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(PRIVATE_MARKER);
    }
}
