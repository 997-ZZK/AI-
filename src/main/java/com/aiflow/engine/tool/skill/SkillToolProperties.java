package com.aiflow.engine.tool.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 技能库（本机 SKILL.md 目录）配置。
 *
 * <p>为什么值得单独做一层：Codex / Claude 这类 Agent 的「技能」本质是**带元数据的本地知识文件** ——
 * 目录里有 name、description 与正文，正好是「工具清单 + 可读取的说明」这一对。把它们接进引擎，
 * 工作流就能把某个技能的正文当成一段可注入的指令，而不是把同样的内容在提示词里复制一遍。
 *
 * <p>风险等级与 HTTP 工具、MCP 服务端都不同：**只读本地文件、不出网、不起进程**。所以它有自己的开关，
 * 但仍受 {@code aiflow.tools.enabled} 总闸约束（统一「引擎能调工具」这件事的心智模型）。
 *
 * @param enabled       来源闸（默认 false）
 * @param roots         扫描根目录，前面的优先级高（同名技能以先出现的为准）
 * @param maxBodyChars  skill.read 返回正文的字符上限（超出截断并标注，避免把长文档塞爆提示词）
 * @param maxListEntries skill.list 单次返回条数上限
 * @param maxDepth      目录递归深度（防止误把整个用户目录当技能根扫一遍）
 */
@ConfigurationProperties(prefix = "aiflow.tools.skills")
public record SkillToolProperties(
        @DefaultValue("false") boolean enabled,
        List<String> roots,
        @DefaultValue("20000") int maxBodyChars,
        @DefaultValue("50") int maxListEntries,
        @DefaultValue("4") int maxDepth
) {

    public SkillToolProperties {
        roots = roots == null ? List.of() : List.copyOf(roots);
    }
}
