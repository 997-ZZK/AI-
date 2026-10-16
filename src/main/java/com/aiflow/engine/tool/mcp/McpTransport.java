package com.aiflow.engine.tool.mcp;

/**
 * MCP 的传输形态。
 *
 * <p>三种形态的权限面与状态机完全不同，所以必须分开看：
 * <ul>
 *   <li>{@link #STDIO}：本地子进程，newline-delimited JSON-RPC。风险是「能在这台机器上起什么进程」</li>
 *   <li>{@link #HTTP}：远程 Streamable HTTP（单端点 POST，响应可以是 JSON 也可以是 SSE 流）。
 *       风险是「能往哪个地址发请求、能带什么凭证」</li>
 *   <li>{@link #SSE}：老式 SSE 双端点（GET 常驻连接发现端点 + POST 投递消息，规范 2024-11-05）。
 *       与 HTTP 同属远程形态，但**状态机是两套**：一条长连接 + 请求/响应分离 + 断线要自己重连</li>
 * </ul>
 *
 * <p>规范 2025-03-26 起用 Streamable HTTP 取代了 SSE 双端点，但存量服务端（Claude 的 {@code type: "sse"}）
 * 仍然不少，所以两种都支持：形态由配置显式声明（{@code transport: auto|stdio|http|sse}），
 * 不靠「url 里有没有 /sse」去猜 —— 猜错的代价是拿一种状态机去套另一种协议。
 */
public enum McpTransport {

    STDIO("stdio"),
    HTTP("http"),
    SSE("sse");

    private final String wireName;

    McpTransport(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** 远程形态（要过出网白名单，凭证只从环境变量来）。 */
    public boolean remote() {
        return this != STDIO;
    }

    public static McpTransport parse(String value, String fallbackSource) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "auto" -> null;
            case "stdio" -> STDIO;
            case "http", "streamable-http", "streamable" -> HTTP;
            case "sse" -> SSE;
            default -> throw new McpCallException("MCP 服务端 " + fallbackSource + " 的 transport 取值非法: "
                    + value + "（可用 auto / stdio / http / sse）");
        };
    }
}
