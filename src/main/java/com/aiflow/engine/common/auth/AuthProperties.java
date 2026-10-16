package com.aiflow.engine.common.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 鉴权配置（{@code aiflow.auth.*}）。
 *
 * <p>这里只声明「谁是谁」和「令牌放在哪个变量里」——**令牌值永远不进 yml**
 * （与 MCP 的 {@code auth-token-env} 同一条纪律）。取舍与不做清单见 docs/auth-design.md。
 *
 * @param enabled  总开关，默认关：本地开发与控制台零配置可用；关着的时候启动会打 WARN
 *                 （decidedBy / actor 是自报字段，审批人名单不是安全边界）
 * @param subjects 允许的主体名单
 */
@ConfigurationProperties(prefix = "aiflow.auth")
public record AuthProperties(@DefaultValue("false") boolean enabled,
                             @DefaultValue({}) List<SubjectProperties> subjects) {

    /**
     * @param id          主体 id（会写进 decidedBy / actor 与审计）
     * @param displayName 给人看的名字，可空（回落到 id）
     * @param tokenEnv    环境变量名，值才是令牌
     */
    public record SubjectProperties(String id, String displayName, String tokenEnv) {
    }

    public static AuthProperties defaults() {
        return new AuthProperties(false, List.of());
    }
}
