package com.aiflow.engine.integration;

import com.aiflow.engine.retrieval.RetrievalException;

/** 固定错误元数据；不链接含原始 URL、凭证或报文的底层异常。 */
public final class CloudKnowledgeBaseException extends RetrievalException {
    public enum Reason {
        CONFIGURATION, AUTHENTICATION, RATE_LIMITED, UPSTREAM, TIMEOUT, TRANSPORT,
        INVALID_RESPONSE, RESPONSE_TOO_LARGE, REQUEST_TOO_LARGE, OVERLOADED, CANCELED
    }

    private final Reason reason;
    private final Integer httpStatus;
    private final boolean retryable;

    public CloudKnowledgeBaseException(Reason reason, Integer httpStatus, boolean retryable) {
        super("云端知识库调用失败（" + reason.name() + (httpStatus == null ? "" : ", HTTP " + httpStatus) + "）", true);
        this.reason = reason;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public Reason reason() { return reason; }
    public Integer httpStatus() { return httpStatus; }
    public boolean retryable() { return retryable; }
}
