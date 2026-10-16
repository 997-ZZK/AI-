package com.aiflow.engine.ingest;

/**
 * OCR 不可用或失败。
 *
 * <p>单独一个异常类型，是为了让调用方能区分「这份文件的问题」（解析器抛别的）与
 * 「识别能力的问题」（这里）：前者要用户改文件，后者要用户改配置或换档位。
 */
public class OcrException extends RuntimeException {

    public enum Reason { DISABLED, LIMIT_EXCEEDED, NO_TEXT, UNAVAILABLE, CALL_FAILED }

    private final Reason reason;

    public OcrException(String message) {
        this(message, null, null);
    }

    public OcrException(String message, Throwable cause) {
        this(message, cause, null);
    }

    private OcrException(String message, Throwable cause, Reason reason) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * 创建由文档解析链路生成、允许向调用方展示的受控诊断。
     *
     * <p>只供解析器自身的开关、页数与空文本校验使用；provider 边界只按固定 reason 重建异常。
     */
    static OcrException diagnostic(Reason reason, String message) {
        return new OcrException(message, null, reason);
    }

    public static OcrException unavailable() {
        return new OcrException("OCR 不可用：没有具备 vision 能力的档位", null, Reason.UNAVAILABLE);
    }

    public static OcrException callFailed() {
        return new OcrException("OCR 调用失败：视觉识别请求未完成", null, Reason.CALL_FAILED);
    }

    public final Reason reason() {
        return reason;
    }

    OcrException diagnosticCopy() {
        return reason == null ? callFailed() : new OcrException(super.getMessage(), null, reason);
    }
}
