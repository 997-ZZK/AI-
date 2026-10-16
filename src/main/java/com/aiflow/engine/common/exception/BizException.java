package com.aiflow.engine.common.exception;

import com.aiflow.engine.common.api.ErrorCode;

public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object data;

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getData() {
        return data;
    }
}
