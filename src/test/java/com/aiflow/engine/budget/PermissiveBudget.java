package com.aiflow.engine.budget;

import com.aiflow.engine.config.BudgetProperties;

import java.time.Clock;

/**
 * 测试用的「不限额度」配置：不计数、不降档、不拦截。
 *
 * <p>额度的规则用例集中在 {@code TokenBudgetServiceTest}，端到端用例在 {@code BudgetIntegrationTest}；
 * 其余测试只关心自己的主题时用这个，免得一份额度把无关用例染上颜色。
 */
public final class PermissiveBudget {

    private PermissiveBudget() {
    }

    public static BudgetProperties properties() {
        return new BudgetProperties(false, -1, 0.8, "Asia/Shanghai", "额度已用尽", "memory");
    }

    public static TokenBudgetService service() {
        return new TokenBudgetService(new InMemoryTokenUsageStore(), properties(), Clock.systemUTC());
    }
}