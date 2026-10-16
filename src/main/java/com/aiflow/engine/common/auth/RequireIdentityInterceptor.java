package com.aiflow.engine.common.auth;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@link RequireIdentity} 的执行者：没有已认证主体就 401。
 *
 * <p>抛 {@link BizException} 而不是自己写响应：preHandle 抛出的异常同样会走
 * {@code @RestControllerAdvice}，错误包装与其它接口保持一致（同一套 code / message / traceId）。
 *
 * <p>**鉴权关闭时 {@code @RequireIdentity} 不生效**（注解写的是"这个接口需要身份"，而"是否需要身份"由部署开关决定）：
 * 否则本地零配置开发会被自己的注解拦死，且错误信息是「请带令牌」—— 与「服务端根本没开鉴权」南辕北辙。
 * 这个取舍有代价：关着鉴权时这两个接口就是自报身份（启动 WARN 已明说）。
 */
@Component
public class RequireIdentityInterceptor implements HandlerInterceptor {

    private final SubjectRegistry registry;

    public RequireIdentityInterceptor(SubjectRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!registry.enabled()) {
            return true;
        }
        if (!(handler instanceof HandlerMethod method) || !requiresIdentity(method)) {
            return true;
        }
        if (IdentityContext.current() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "该接口需要身份：请在 Authorization 头带 Bearer 令牌"
                    + "（GET /api/v1/auth/whoami 可自检；见 docs/auth-design.md）");
        }
        return true;
    }

    private static boolean requiresIdentity(HandlerMethod method) {
        return method.hasMethodAnnotation(RequireIdentity.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RequireIdentity.class);
    }
}
