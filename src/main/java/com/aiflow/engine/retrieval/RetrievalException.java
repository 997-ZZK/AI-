package com.aiflow.engine.retrieval;

/** 检索链路异常：向量化失败、所有来源都不可用、维度不符等。 */
public class RetrievalException extends RuntimeException {

    private final boolean safeMessage;

    public RetrievalException(String message) {
        this(message, null, false);
    }

    public RetrievalException(String message, Throwable cause) {
        this(message, cause, false);
    }

    protected RetrievalException(String message, boolean safeMessage) {
        this(message, null, safeMessage);
    }

    protected RetrievalException(String message, Throwable cause, boolean safeMessage) {
        super(message, cause);
        this.safeMessage = safeMessage;
    }

    /**
     * 创建由引擎自身生成、允许向调用方展示的诊断。provider 原始异常不得使用此入口。
     */
    public static RetrievalException safe(String message) {
        return new RetrievalException(message, null, true);
    }

    public final boolean hasSafeMessage() {
        return safeMessage;
    }
}
