package com.aiflow.engine.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP {@code tools/call} 的归一化结果。
 *
 * <p>MCP 的 content 是数组（text / image / resource…），这里只抽 text 部分拼成字符串；
 * 非文本内容保留在 {@code raw} 里，避免「引擎看不懂就当没有」——真要消费图片再单独做。
 *
 * @param text  text 类内容拼接结果（没有 text 时是 content 的 JSON 字面量）
 * @param error 服务端标记的 isError
 * @param raw   原始 result，落进节点快照供排查
 */
public record McpToolResult(String text, boolean error, JsonNode raw) {
}
