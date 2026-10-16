package com.aiflow.engine.audio;

/**
 * 调用方音频不符合本地契约时抛出。
 *
 * <p>异常只携带固定原因和固定消息，Web 层必须按 {@link Reason} 分流，不能
 * 依赖可变的本地化文本。这里不保留调用方正文、厂商响应或异常 cause。</p>
 */
public class AudioValidationException extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST("音频请求无效"),
        MALFORMED_WAV("WAV 音频结构无效"),
        UNSUPPORTED_MEDIA("不支持的音频媒体类型"),
        UNSUPPORTED_PCM_PARAMS("不支持的 WAV PCM 参数"),
        SIZE_LIMIT("音频文件超过大小限制"),
        DURATION_LIMIT("音频时长超过限制"),
        TEXT_LIMIT("合成文本超过长度限制");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final Reason reason;

    public AudioValidationException(Reason reason) {
        super((reason == null ? Reason.INVALID_REQUEST : reason).message);
        this.reason = reason == null ? Reason.INVALID_REQUEST : reason;
    }

    public Reason reason() {
        return reason;
    }
}
