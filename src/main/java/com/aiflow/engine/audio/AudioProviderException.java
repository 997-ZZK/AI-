package com.aiflow.engine.audio;

/** ASR/TTS provider 不可用或调用失败；只保留脱敏后的固定错误描述。 */
public class AudioProviderException extends RuntimeException {

    public enum Reason {
        UNAVAILABLE("音频能力不可用"),
        TIMEOUT("音频 provider 调用超时"),
        REJECTED("音频 provider 拒绝请求"),
        INVALID_RESPONSE("音频 provider 返回无效响应"),
        CALL_FAILED("音频 provider 调用失败");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;

    public AudioProviderException(Reason reason) {
        super((reason == null ? Reason.CALL_FAILED : reason).message);
        this.reason = reason == null ? Reason.CALL_FAILED : reason;
    }

    public AudioProviderException(Reason reason, Throwable ignoredCause) {
        // 不把厂商异常挂在 cause 上，避免其响应正文、请求标识或凭证出现在日志链中。
        this(reason);
    }

    public Reason reason() {
        return reason;
    }
}
