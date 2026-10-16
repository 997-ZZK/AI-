package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 每用户 token 预算。
 *
 * <p>为什么按「用户」而不是按「会话」或「全局」：额度是钱，钱是用户/租户的属性。按会话算等于
 * 换个会话就能续命；按全局算等于一个用户能把所有人的额度吃光。
 *
 * <p>两个阈值：
 * <ul>
 *   <li>软阈值（{@code soft-threshold-ratio}）：达到后本用户的新请求优先走便宜档位（见
 *       {@code aiflow.provider.cheap-tiers}）—— 降级而不是中断，用户还能用，只是不再用最贵的档。</li>
 *   <li>硬上限（{@code per-user-daily-tokens}）：达到后直接返回预设话术，不再产生任何模型开销。</li>
 * </ul>
 *
 * <p>口径说明：判定在调用前、扣减在调用后，所以最后一个请求可能略微超出上限。
 * 这是刻意换来的：为了精确而不把已经生成的回答丢掉，比超支几十个 token 更糟。
 *
 * @param enabled              总开关；关闭时不计数也不拦截（返回 NORMAL）
 * @param perUserDailyTokens   每用户每日 token 上限，&lt;=0 表示不限
 * @param softThresholdRatio   软阈值比例（0~1），达到即降档
 * @param resetZone            额度按自然日重置的时区（默认 Asia/Shanghai，跨天口径要写死，别跟着机器漂）
 * @param exhaustedReply       额度用尽时返回的预设话术
 * @param store                计数存储：memory（单实例，默认）| redis（多实例共享）
 */
@ConfigurationProperties(prefix = "aiflow.budget")
public record BudgetProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("200000") long perUserDailyTokens,
        @DefaultValue("0.8") double softThresholdRatio,
        @DefaultValue("Asia/Shanghai") String resetZone,
        @DefaultValue("今日 AI 用量已达上限，请明天再试或联系管理员。") String exhaustedReply,
        @DefaultValue("memory") String store
) {

    public BudgetProperties {
        // 越界的比例会让阈值永远触发或永不触发，都是「配置错了却没人发现」的典型；
        // 这里夹到合法区间并在日志可见的行为上保持确定（不做静默的第三种解释）。
        if (!(softThresholdRatio > 0) || softThresholdRatio >= 1) {
            softThresholdRatio = 0.8;
        }
    }

    /** 不限额度：不计数、不降档、不拦截。 */
    public boolean unlimited() {
        return perUserDailyTokens <= 0;
    }

    public boolean enforced() {
        return enabled && !unlimited();
    }

    public static BudgetProperties defaults() {
        return new BudgetProperties(true, 200_000, 0.8, "Asia/Shanghai",
                "今日 AI 用量已达上限，请明天再试或联系管理员。", "memory");
    }
}