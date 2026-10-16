package com.aiflow.engine.tool;

/**
 * 工具来源。来源不同，护栏的重点也不同，所以要在注册表里显式区分而不是靠命名约定：
 * <ul>
 *   <li>{@link #HTTP}：引擎主动出网，防线是 SSRF 四道校验 + host 白名单 + 端口白名单</li>
 *   <li>{@link #MCP}：拉起本地进程并按 MCP 协议通信，防线是「来源开关 + 命令由配置声明 + 工具白名单 + 超时即杀进程」</li>
 *   <li>{@link #SKILL}：只读本机技能文件（SKILL.md），不出网、不起进程，防线是目录白名单 + 返回体上限</li>
 * </ul>
 */
public enum ToolKind {
    HTTP,
    MCP,
    SKILL
}
