package com.aiflow.engine.tool;

/**
 * 工具调用在传输层失败（连接被拒、超时、读流中断）。
 *
 * <p>与 {@link ToolBlockedException} 分开：这一类是外部依赖问题，节点可以重试、可以降级；
 * 被护栏拦下则是策略结论，重试多少次都一样。
 */
public class ToolInvocationException extends RuntimeException {

    public ToolInvocationException(String message) {
        super(message);
    }

    public ToolInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}