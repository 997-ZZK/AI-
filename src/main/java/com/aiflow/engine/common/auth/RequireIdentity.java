package com.aiflow.engine.common.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在**需要已认证身份**的接口上（方法或类）。没有身份 -&gt; 401（由 {@link RequireIdentityInterceptor} 执行）。
 *
 * <p>为什么是「按接口显式声明」而不是「全量 API 鉴权」：本地开发与控制台要零配置可用，
 * 而当前真正的「身份即边界」只有审批决策与取消运行两处（docs/auth-design.md §3.3、§7）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireIdentity {
}
