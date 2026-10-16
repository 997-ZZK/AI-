package com.aiflow.engine.tool;

/**
 * 工具调用被护栏拒绝：不在白名单、命中内网地址、超出调用次数或循环阈值。
 *
 * <p>单独一个异常类型是为了让节点能把「被护栏拦下」与「调用失败」分开：前者是策略结论（用户需要改配置或改输入），
 * 后者是外部依赖问题（可以重试、可以降级）。
 */
public class ToolBlockedException extends RuntimeException {

    public ToolBlockedException(String message) {
        super(message);
    }
}