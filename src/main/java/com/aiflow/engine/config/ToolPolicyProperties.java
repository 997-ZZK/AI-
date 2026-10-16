package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 工具授权策略：**谁能调什么工具**。
 *
 * <p>为什么授权要单独一层而不是继续靠"工具开没开"：`enabled` 是**能力开关**（这个引擎能不能调工具），
 * 策略是**权限开关**（这个人能不能调这个工具）。真实系统里这两件事必须分开 —— 同一个 MCP 服务端里的
 * 读文件和写文件是两个工具，给不给某个人用完全是另一回事。
 *
 * <p>规则按声明顺序匹配，**首个命中的规则生效**（第一条说了算，后面的不再看）：这比"多条规则合并"好推理，
 * 也便于在配置里用一条 `tool: "*"` 收口。用户匹配支持 `*` 通配；`deny-users` 优先级高于 `allow-users`。
 *
 * @param defaultAllow 没有规则命中时是否放行（默认 true = 保持向后兼容，加规则是为了收紧而不是默认全拒）
 * @param requireUser  没有 userId 的调用一律拒绝（定时任务、后台补数这类没有用户上下文的场景）
 * @param rules        规则列表，顺序即优先级
 */
@ConfigurationProperties(prefix = "aiflow.tools.policy")
public record ToolPolicyProperties(
        @DefaultValue("true") boolean defaultAllow,
        @DefaultValue("false") boolean requireUser,
        List<Rule> rules
) {

    public ToolPolicyProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * @param tool       工具 id 匹配式（Ant 风格：`mcp.*`、`skill.*`、`*`）
     * @param allowUsers 允许的用户（空 = 不额外限制；`*` 表示任何已登录用户）
     * @param denyUsers  禁止的用户（优先级高于 allowUsers）
     * @param reason     拒绝时给用户看的原因（留空则用默认文案）
     */
    public record Rule(String tool, List<String> allowUsers, List<String> denyUsers, String reason) {

        public Rule {
            allowUsers = allowUsers == null ? List.of() : List.copyOf(allowUsers);
            denyUsers = denyUsers == null ? List.of() : List.copyOf(denyUsers);
        }
    }
}
