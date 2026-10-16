package com.aiflow.engine.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个 MCP 服务端的客户端：与传输形态无关的那部分契约。
 *
 * <p>抽这层接口不是因为「以后可能有别的实现」这种假设，而是现在就有两种实现（stdio / Streamable HTTP），
 * 而 {@link McpServerManager} 的装配、发现、调用逻辑两者完全一样 —— 差别只在「怎么把一条 JSON-RPC 消息送出去、
 * 把响应拿回来」。把边界划在传输层，manager 与上层节点就不需要到处判断形态。
 */
public interface McpClient extends AutoCloseable {

    String serverId();

    McpServerConfig config();

    /** 握手：{@code initialize} + {@code notifications/initialized}。已握手时是空操作。 */
    void handshake();

    JsonNode serverInfo();

    JsonNode listTools();

    JsonNode callTool(String toolName, JsonNode arguments);

    /**
     * 发一条带 id 的 JSON-RPC 请求并等响应。
     *
     * <p>{@code initialize} 自身不会触发递归握手（否则握手失败会变成栈溢出而不是一条错误消息）。
     */
    JsonNode request(String method, JsonNode params, long timeoutMs);

    /**
     * 诊断用：这个服务端的凭证从哪来（静态环境变量 / OAuth 动态令牌 / 无）。
     *
     * <p>**只描述来源，不返回任何令牌内容** —— 它会被写进 {@code /api/v1/tools} 的快照，
     * 排障时要能一眼看出「这条服务端到底带没带凭证、是哪种」，但不能把密钥泄漏到接口上。
     */
    default String describeAuth() {
        return "none";
    }

    @Override
    void close();
}
