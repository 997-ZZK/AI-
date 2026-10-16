package com.aiflow.engine.tool;

import com.aiflow.engine.config.ToolPolicyProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权判定：规则顺序、通配、deny 优先、无用户上下文这几种边界。
 *
 * <p>这些用例的价值不在覆盖率，而在于把**语义**钉死：第一条命中就生效、deny 压 allow、
 * 空白名单不等于"所有人"。这三条任意一条理解错了，配出来的策略都会和预期不一样。
 */
class ToolAuthorizerTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static ToolAuthorizer authorizer(boolean defaultAllow, boolean requireUser,
                                             ToolPolicyProperties.Rule... rules) {
        return new ToolAuthorizer(new ToolPolicyProperties(defaultAllow, requireUser, List.of(rules)));
    }

    @Test
    void allowsByDefaultWhenNoRuleMatches() {
        ToolAuthorizer authorizer = authorizer(true, false);

        assertThat(authorizer.check(ALICE, "skill.read").allowed()).isTrue();
    }

    @Test
    void deniesWhenDefaultIsClosed() {
        ToolAuthorizer authorizer = authorizer(false, false);

        ToolAuthorizer.Decision decision = authorizer.check(ALICE, "skill.read");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("default-allow=false");
    }

    @Test
    void emptyAllowListMeansEveryone() {
        ToolAuthorizer authorizer = authorizer(false, false,
                new ToolPolicyProperties.Rule("skill.*", List.of(), List.of(), null));

        assertThat(authorizer.check(BOB, "skill.read").allowed()).isTrue();
        assertThat(authorizer.check(BOB, "mcp.x.y").allowed()).isFalse();
    }

    @Test
    void deniesUserOutsideAllowList() {
        ToolAuthorizer authorizer = authorizer(true, false,
                new ToolPolicyProperties.Rule("mcp.*", List.of(ALICE.toString()), List.of(), "写操作受限"));

        assertThat(authorizer.check(ALICE, "mcp.fs.write").allowed()).isTrue();
        ToolAuthorizer.Decision denied = authorizer.check(BOB, "mcp.fs.write");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isEqualTo("写操作受限");
        assertThat(denied.rule()).isEqualTo("rule[0] mcp.*");
    }

    @Test
    void denyWinsOverAllow() {
        ToolAuthorizer authorizer = authorizer(true, false,
                new ToolPolicyProperties.Rule("mcp.*", List.of("*"), List.of(BOB.toString()), null));

        assertThat(authorizer.check(ALICE, "mcp.fs.read").allowed()).isTrue();
        assertThat(authorizer.check(BOB, "mcp.fs.read").allowed()).isFalse();
    }

    @Test
    void firstMatchingRuleWins() {
        ToolAuthorizer authorizer = authorizer(true, false,
                new ToolPolicyProperties.Rule("mcp.fs.*", List.of(ALICE.toString()), List.of(), null),
                new ToolPolicyProperties.Rule("mcp.*", List.of("*"), List.of(), null));

        // 第一条 restricts 到 alice；bob 被它挡住，不会"漏"到第二条的放开规则
        assertThat(authorizer.check(BOB, "mcp.fs.read").allowed()).isFalse();
        assertThat(authorizer.check(BOB, "mcp.other.read").allowed()).isTrue();
    }

    @Test
    void requiresUserContextWhenConfigured() {
        ToolAuthorizer authorizer = authorizer(true, true);

        ToolAuthorizer.Decision decision = authorizer.check(null, "skill.list");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("没有用户上下文");
    }

    @Test
    void nullUserOnlyMatchesExplicitStar() {
        ToolAuthorizer open = authorizer(true, false,
                new ToolPolicyProperties.Rule("mcp.*", List.of("*"), List.of(), null));
        ToolAuthorizer named = authorizer(true, false,
                new ToolPolicyProperties.Rule("mcp.*", List.of(ALICE.toString()), List.of(), null));

        assertThat(open.check(null, "mcp.fs.read").allowed()).isTrue();
        // 空白名单不能等价于"所有人"：没有用户上下文时，具名规则应当拒绝
        assertThat(named.check(null, "mcp.fs.read").allowed()).isFalse();
    }
}
