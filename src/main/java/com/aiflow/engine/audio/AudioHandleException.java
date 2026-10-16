package com.aiflow.engine.audio;

/** Fixed, deliberately non revealing failures for run-local audio handles. */
public class AudioHandleException extends RuntimeException {

    public enum Reason {
        RUN_NOT_OPEN("音频运行资源未开放"),
        HANDLE_INVALID("音频句柄无效"),
        HANDLE_EXPIRED("音频句柄已过期"),
        HANDLE_OWNER_MISMATCH("音频句柄不属于当前运行"),
        CAPACITY("音频句柄容量不足");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;

    public AudioHandleException(Reason reason) {
        super((reason == null ? Reason.HANDLE_INVALID : reason).message);
        this.reason = reason == null ? Reason.HANDLE_INVALID : reason;
    }

    public Reason reason() {
        return reason;
    }
}
