package com.aiflow.engine.common.auth;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自报字段与令牌身份的取舍规则（审批 decidedBy / 取消 actor 共用）。
 *
 * <p>第一条是**兼容性承诺**：鉴权关闭时自报字段照旧生效 —— 本地开发与控制台不因为引入鉴权而改行为。
 */
class IdentityContextTest {

    @AfterEach
    void tearDown() {
        IdentityContext.clear();
    }

    @Test
    void withoutIdentityTheSelfReportedActorWins() {
        assertThat(IdentityContext.resolveActor("alice", "decidedBy")).isEqualTo("alice");
        assertThat(IdentityContext.resolveActor("  alice  ", "decidedBy")).isEqualTo("alice");
        assertThat(IdentityContext.resolveActor(null, "decidedBy")).isNull();
        assertThat(IdentityContext.resolveActor("   ", "decidedBy")).isNull();
    }

    @Test
    void withIdentityTheTokenWinsEvenWhenSelfReportedIsMissing() {
        IdentityContext.set(new Subject("reviewer-a", "审批人 A"));

        assertThat(IdentityContext.resolveActor(null, "decidedBy")).isEqualTo("reviewer-a");
        assertThat(IdentityContext.resolveActor("reviewer-a", "decidedBy")).isEqualTo("reviewer-a");
    }

    @Test
    void conflictingSelfReportIsRejectedRatherThanSilentlyIgnored() {
        IdentityContext.set(new Subject("reviewer-a", "审批人 A"));

        assertThatThrownBy(() -> IdentityContext.resolveActor("reviewer-b", "decidedBy"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("令牌身份")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void clearingRemovesTheIdentity() {
        IdentityContext.set(new Subject("reviewer-a", "审批人 A"));
        IdentityContext.clear();

        assertThat(IdentityContext.current()).isNull();
        assertThat(IdentityContext.resolveActor("alice", "actor")).isEqualTo("alice");
    }
}
