package com.aiflow.engine.api;

import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.common.auth.IdentityContext;
import com.aiflow.engine.common.auth.Subject;
import com.aiflow.engine.common.auth.SubjectRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份自检：带着令牌问「我是谁」。
 *
 * <p>匿名也返回 200（{@code authenticated=false}）—— 它是给控制台与排障用的，故意不 401：
 * 「为什么我调不动」第一个要排除的就是「服务端到底认没认出我」。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final SubjectRegistry registry;

    public AuthController(SubjectRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/whoami")
    public ApiResponse<WhoAmI> whoami() {
        Subject subject = IdentityContext.current();
        return ApiResponse.ok(new WhoAmI(registry.enabled(), subject != null,
                subject == null ? null : new SubjectView(subject.id(), subject.displayName())));
    }

    public record SubjectView(String id, String displayName) {
    }

    public record WhoAmI(boolean enabled, boolean authenticated, SubjectView subject) {
    }
}
