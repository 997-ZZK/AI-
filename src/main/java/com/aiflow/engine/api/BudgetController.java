package com.aiflow.engine.api;

import com.aiflow.engine.api.service.UserService;
import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 每用户额度状态：用了多少、离降档还有多远、什么时候重置。
 *
 * <p>做这个接口的理由：额度一旦生效，「为什么这次回答变慢了/换了档位」就必须能被解释。
 * 只把用量塞进日志，等于把问题留给下一次值班的人。
 */
@RestController
@RequestMapping("/api/v1/budget")
public class BudgetController {

    private final TokenBudgetService tokenBudgetService;
    private final UserService userService;

    public BudgetController(TokenBudgetService tokenBudgetService, UserService userService) {
        this.tokenBudgetService = tokenBudgetService;
        this.userService = userService;
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<UsageView> usage(@PathVariable UUID userId) {
        // 不存在的用户给 404，而不是返回一份看起来正常的 0 用量
        userService.get(userId);
        TokenBudgetService.Snapshot snapshot = tokenBudgetService.snapshot(userId);
        return ApiResponse.ok(new UsageView(userId, snapshot.used(), snapshot.limit(), snapshot.ratio(),
                snapshot.status().name(), snapshot.resetAt(), snapshot.store()));
    }

    public record UsageView(UUID userId, long used, long limit, double ratio, String status, Instant resetAt,
                           String store) {
    }
}