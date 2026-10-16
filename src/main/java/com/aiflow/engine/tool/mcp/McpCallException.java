package com.aiflow.engine.tool.mcp;

/** MCP 调用失败（握手失败、超时、服务端返回 error、进程退出）。 */
public class McpCallException extends RuntimeException {

    public McpCallException(String message) {
        super(message);
    }

    public McpCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
