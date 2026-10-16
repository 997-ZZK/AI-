package com.aiflow.engine.tool;

import com.aiflow.engine.config.ToolPolicyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.UUID;

/**
 * 工具授权：在"工具存在且已注册"之后、真正调用之前，回答"这个人能不能用这个工具"。
 *
 * <p>为什么把这一步放在执行器里而不是注册表里：注册表是**装配期**概念（有哪些工具），
 * 授权是**运行期**概念（这次调用是谁发起的）。把用户维度塞进注册表会让"工具清单"变成
 * "某个人能看到的工具清单"，缓存、并发与测试都会跟着变复杂。
 *
 * <p>匹配顺序与语义见 {@link ToolPolicyProperties}。任何一条路径上的拒绝都必须给出**可读原因**：
 * 用户看到"工具未授权"时，下一个问题必然是"为什么"，而这个答案只有策略层知道。
 */
@Component
public class ToolAuthorizer {

    /** 拒绝时的错误前缀：执行器据此把审计结果标成 DENIED 而不是普通的 FAILED。 */
    public static final String DENIED_PREFIX = "工具未授权: ";

    private static final Logger log = LoggerFactory.getLogger(ToolAuthorizer.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final ToolPolicyProperties properties;

    public ToolAuthorizer(ToolPolicyProperties properties) {
        this.properties = properties;
    }

    /**
     * 这个错误是不是「策略拒绝」。
     *
     * <p>用途不止标记审计口径：拒绝是**确定性**失败 —— 同一个用户、同一条策略，重试多少次结果都一样。
     * 引擎据此不对被拒的节点做重试（重试只多等一轮退避，还会在审计里留下两行「被拒」）。
     */
    public static boolean isDenied(String error) {
        return error != null && error.startsWith(DENIED_PREFIX);
    }

    public Decision check(UUID userId, String toolId) {
        if (properties.requireUser() && userId == null) {
            return Decision.deny("这次调用没有用户上下文（require-user=true）");
        }
        String user = userId == null ? null : userId.toString();
        List<ToolPolicyProperties.Rule> rules = properties.rules();
        for (int index = 0; index < rules.size(); index++) {
            ToolPolicyProperties.Rule rule = rules.get(index);
            if (rule.tool() == null || !MATCHER.match(rule.tool(), toolId)) {
                continue;
            }
            String label = "rule[" + index + "] " + rule.tool();
            if (matches(rule.denyUsers(), user)) {
                return Decision.deny(describe(rule, "该用户被明确禁止使用这个工具"), label);
            }
            if (!rule.allowUsers().isEmpty() && !matches(rule.allowUsers(), user)) {
                return Decision.deny(describe(rule, "该用户不在允许名单里"), label);
            }
            return Decision.allow(label);
        }
        if (properties.defaultAllow()) {
            return Decision.allow("default-allow");
        }
        return Decision.deny("没有规则命中且 default-allow=false");
    }

    private static boolean matches(List<String> patterns, String user) {
        if (patterns.isEmpty()) {
            return false;
        }
        if (user == null) {
            // 没有用户上下文时，只有"显式写了 * "的规则才算命中 —— 空白名单不能等价于"所有人"
            return patterns.contains("*");
        }
        for (String pattern : patterns) {
            if ("*".equals(pattern) || pattern.equals(user)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(ToolPolicyProperties.Rule rule, String fallback) {
        String custom = rule.reason();
        if (custom != null && !custom.isBlank()) {
            return custom.strip();
        }
        return fallback + "（" + (rule.tool() == null ? "*" : rule.tool()) + "）";
    }

    /**
     * 授权结论。
     *
     * @param allowed 是否放行
     * @param reason  拒绝原因（放行时为命中的规则标签）
     * @param rule    命中的规则标签，便于排障时直接定位到配置里那一行
     */
    public record Decision(boolean allowed, String reason, String rule) {

        static Decision allow(String rule) {
            return new Decision(true, rule, rule);
        }

        static Decision deny(String reason) {
            return new Decision(false, reason, null);
        }

        static Decision deny(String reason, String rule) {
            log.debug("工具授权拒绝 rule={} reason={}", rule, reason);
            return new Decision(false, reason, rule);
        }
    }
}
