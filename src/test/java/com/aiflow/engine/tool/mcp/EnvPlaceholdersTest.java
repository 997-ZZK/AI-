package com.aiflow.engine.tool.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 配置里的 ${VAR} 占位符解析：能不能替换、缺变量时是否安静地留空（不能）。 */
class EnvPlaceholdersTest {

    private static final java.util.function.Function<String, String> ENV =
            name -> "TOKEN".equals(name) ? "s3cret" : null;

    @Test
    void replacesPlaceholderWithEnvironmentValue() {
        assertThat(EnvPlaceholders.resolve("Bearer ${TOKEN}", ENV)).isEqualTo("Bearer s3cret");
        assertThat(EnvPlaceholders.resolve("${TOKEN}/${TOKEN}", ENV)).isEqualTo("s3cret/s3cret");
    }

    @Test
    void keepsPlainTextUntouched() {
        assertThat(EnvPlaceholders.resolve("plain-value", ENV)).isEqualTo("plain-value");
        assertThat(EnvPlaceholders.resolve(null, ENV)).isNull();
    }

    @Test
    void usesDefaultWhenVariableMissing() {
        assertThat(EnvPlaceholders.resolve("tenant-${TENANT:-public}", ENV)).isEqualTo("tenant-public");
        assertThat(EnvPlaceholders.resolve("token-${TOKEN:-fallback}", ENV)).isEqualTo("token-s3cret");
    }

    @Test
    void emptyDefaultMeansEmptyString() {
        assertThat(EnvPlaceholders.resolve("x${TENANT:-}y", ENV)).isEqualTo("xy");
    }

    @Test
    void missingVariableWithoutDefaultFailsFast() {
        assertThatThrownBy(() -> EnvPlaceholders.resolve("Bearer ${MISSING}", ENV))
                .isInstanceOf(McpCallException.class)
                .hasMessageContaining("MISSING")
                .hasMessageContaining("未设置");
    }

    @Test
    void missingClosingBraceIsRejected() {
        assertThatThrownBy(() -> EnvPlaceholders.resolve("Bearer ${TOKEN", ENV))
                .isInstanceOf(McpCallException.class)
                .hasMessageContaining("右花括号");
    }

    @Test
    void resolvesEveryValueInMap() {
        Map<String, String> resolved = EnvPlaceholders.resolveAll(
                Map.of("Authorization", "Bearer ${TOKEN}", "X-Tenant", "${TENANT:-public}"), ENV);

        assertThat(resolved).containsEntry("Authorization", "Bearer s3cret").containsEntry("X-Tenant", "public");
    }
}
