package com.aiflow.engine.voiceprint;

import java.util.Arrays;
import java.util.Locale;

/**
 * 一次短生命周期的声纹样本。
 *
 * <p>这里不冻结采样率、编码或 sidecar 字段；这些是待确认的算法资料。样本只在
 * provider 调用期间以内存传递，字节数组始终做防御性复制，{@link #toString()} 不
 * 暴露音频正文。</p>
 */
public record VoiceprintSample(byte[] bytes, String mediaType) {

    public VoiceprintSample {
        bytes = bytes == null ? new byte[0] : Arrays.copyOf(bytes, bytes.length);
        mediaType = mediaType == null || mediaType.isBlank()
                ? null : mediaType.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    /** 供 provider 做最小输入门禁；不代表算法质量校验。 */
    public boolean usable() {
        return bytes.length > 0;
    }

    @Override
    public String toString() {
        return "VoiceprintSample[byteLength=" + bytes.length + ", mediaType=" + mediaType + "]";
    }
}
