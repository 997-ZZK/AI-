package com.aiflow.engine.common.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主体名单的装配期校验与令牌解析（纯单元，不起 Spring）。
 *
 * <p>钉住两条：**比较严格**（大小写、前后空格都不宽容 —— 宽容就是给爆破留缝）与
 * **配置错了就起不来**（服务活着但谁都进不来/谁都能进来，比启动失败难查得多）。
 */
class SubjectRegistryTest {

    private static final String TOKEN_A = "token-a";
    private static final String TOKEN_B = "token-b";

    private final MockEnvironment environment = new MockEnvironment();

    @Test
    void resolvesConfiguredTokenToSubject() {
        environment.setProperty("TEST_TOKEN_A", TOKEN_A);
        SubjectRegistry registry = new SubjectRegistry(enabledWith(subject("reviewer-a", "审批人 A", "TEST_TOKEN_A")), environment);

        assertThat(registry.enabled()).isTrue();
        assertThat(registry.resolve(TOKEN_A)).contains(new Subject("reviewer-a", "审批人 A"));
        assertThat(registry.resolve(TOKEN_B)).isEmpty();
    }

    @Test
    void displayNameFallsBackToId() {
        environment.setProperty("TEST_TOKEN_A", TOKEN_A);
        SubjectRegistry registry = new SubjectRegistry(enabledWith(subject("reviewer-a", "  ", "TEST_TOKEN_A")), environment);

        assertThat(registry.resolve(TOKEN_A)).contains(new Subject("reviewer-a", "reviewer-a"));
    }

    @Test
    void tokenMatchIsExactNotForgiving() {
        environment.setProperty("TEST_TOKEN_A", TOKEN_A);
        SubjectRegistry registry = new SubjectRegistry(enabledWith(subject("reviewer-a", null, "TEST_TOKEN_A")), environment);

        assertThat(registry.resolve("TOKEN-A")).as("大小写不同不是同一个令牌").isEmpty();
        assertThat(registry.resolve(" token-a")).as("前后空格不宽容（传输层的空白由 filter 处理）").isEmpty();
        assertThat(registry.resolve("token-a-extra")).isEmpty();
        assertThat(registry.resolve("")).isEmpty();
        assertThat(registry.resolve(null)).isEmpty();
    }

    @Test
    void disabledRegistryReadsNothingEvenWhenTokensAreConfigured() {
        environment.setProperty("TEST_TOKEN_A", TOKEN_A);
        SubjectRegistry registry = new SubjectRegistry(
                new AuthProperties(false, List.of(subject("reviewer-a", null, "TEST_TOKEN_A"))), environment);

        assertThat(registry.enabled()).isFalse();
        assertThat(registry.resolve(TOKEN_A)).isEmpty();
    }

    @Test
    void disabledRegistryToleratesEmptySubjectList() {
        assertThat(new SubjectRegistry(AuthProperties.defaults(), environment).enabled()).isFalse();
    }

    @Test
    void failsFastWhenEnabledWithoutSubjects() {
        assertThatThrownBy(() -> new SubjectRegistry(new AuthProperties(true, List.of()), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subjects");
    }

    @Test
    void failsFastWhenTokenVariableIsMissing() {
        assertThatThrownBy(() -> new SubjectRegistry(enabledWith(subject("reviewer-a", null, "TEST_TOKEN_MISSING")), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEST_TOKEN_MISSING")
                .as("只报变量名，不打印值")
                .hasMessageNotContaining(TOKEN_A);
    }

    @Test
    void failsFastWhenTwoSubjectsShareAToken() {
        environment.setProperty("TEST_TOKEN_A", TOKEN_A);
        environment.setProperty("TEST_TOKEN_B", TOKEN_A);
        assertThatThrownBy(() -> new SubjectRegistry(new AuthProperties(true, List.of(
                subject("reviewer-a", null, "TEST_TOKEN_A"), subject("reviewer-b", null, "TEST_TOKEN_B"))), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("共用");
    }

    @Test
    void failsFastOnDuplicateId() {
        environment.setProperty("TEST_TOKEN_A", TOKEN_A);
        environment.setProperty("TEST_TOKEN_B", TOKEN_B);
        assertThatThrownBy(() -> new SubjectRegistry(new AuthProperties(true, List.of(
                subject("reviewer-a", null, "TEST_TOKEN_A"), subject("reviewer-a", null, "TEST_TOKEN_B"))), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复的 id");
    }

    @Test
    void multipleSubjectsResolveIndependently() {
        environment.setProperty("TEST_TOKEN_A", TOKEN_A);
        environment.setProperty("TEST_TOKEN_B", TOKEN_B);
        SubjectRegistry registry = new SubjectRegistry(new AuthProperties(true, List.of(
                subject("reviewer-a", null, "TEST_TOKEN_A"), subject("reviewer-b", null, "TEST_TOKEN_B"))), environment);

        assertThat(registry.resolve(TOKEN_A).orElseThrow().id()).isEqualTo("reviewer-a");
        assertThat(registry.resolve(TOKEN_B).orElseThrow().id()).isEqualTo("reviewer-b");
    }

    private static AuthProperties enabledWith(AuthProperties.SubjectProperties subject) {
        return new AuthProperties(true, List.of(subject));
    }

    private static AuthProperties.SubjectProperties subject(String id, String displayName, String tokenEnv) {
        return new AuthProperties.SubjectProperties(id, displayName, tokenEnv);
    }
}
