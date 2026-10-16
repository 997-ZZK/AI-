package com.aiflow.engine.common.api;

public enum ErrorCode {

    OK(0, 200, "ok"),
    UNAUTHORIZED(40100, 401, "未认证"),
    BAD_REQUEST(40000, 400, "请求参数错误"),
    NOT_FOUND(40400, 404, "资源不存在"),
    METHOD_NOT_ALLOWED(40500, 405, "请求方法不支持"),
    PAYLOAD_TOO_LARGE(41300, 413, "请求内容超过限制"),
    UNSUPPORTED_MEDIA_TYPE(41500, 415, "不支持的 Content-Type"),
    CONFLICT(40900, 409, "资源状态冲突"),
    FORBIDDEN(40300, 403, "无权执行该操作"),
    GRAPH_INVALID(42200, 422, "工作流定义非法"),
    RATE_LIMITED(42900, 429, "请求过于频繁"),
    CAPABILITY_UNAVAILABLE(50300, 503, "能力不可用"),
    BAD_GATEWAY(50200, 502, "上游服务调用失败"),
    GATEWAY_TIMEOUT(50400, 504, "上游服务调用超时"),
    INTERNAL_ERROR(50000, 500, "服务器内部错误");

    private final int code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    /**
     * 按 HTTP 状态找业务码（本项目里两者一一对应）。找不到就退到 4xx / 5xx 的通用码 ——
     * 宁可给一个宽泛的码，也不要凭空造一个只在这个分支里存在的状态。
     */
    public static ErrorCode fromHttpStatus(int httpStatus) {
        for (ErrorCode candidate : values()) {
            if (candidate.httpStatus == httpStatus) {
                return candidate;
            }
        }
        return httpStatus >= 500 ? INTERNAL_ERROR : BAD_REQUEST;
    }
}
