package com.aiflow.engine.attachment;

import java.util.Locale;
import java.util.Optional;

/**
 * 图片类型：**按文件头嗅探**，不看扩展名。
 *
 * <p>只认这四种：多模态模型的实际支持面就这些，收得比它们宽只会把问题推到调用时报错。
 *
 * <p>放在独立文件里而不是内嵌在 {@link AttachmentStore} 中，是因为它的第二个消费者是文档入库
 * （OCR 前要先知道 MIME）。嗅探这件事只应该有一份实现 —— 两处各写一遍，早晚会出现
 * 「附件能收、入库拒收」这类不一致。
 */
public enum ImageKind {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp");

    private final String mimeType;
    private final String extension;

    ImageKind(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<ImageKind> sniff(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8')) {
            return Optional.of(GIF);
        }
        if (startsWith(bytes, 'R', 'I', 'F', 'F') && bytes.length >= 12
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /**
     * 文件名后缀是否属于受支持的图片。
     *
     * <p>只用于**分诊**（这份上传该交给谁处理），真正的类型仍以文件头嗅探为准 ——
     * 改扩展名不该改变行为，这是附件层已有的约定。
     */
    public static boolean hasImageExtension(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpeg")) {
            return true;
        }
        for (ImageKind kind : values()) {
            if (lower.endsWith("." + kind.extension)) {
                return true;
            }
        }
        return false;
    }

    public static Optional<ImageKind> byMime(String mimeType) {
        if (mimeType == null) {
            return Optional.empty();
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT).strip();
        for (ImageKind kind : values()) {
            if (kind.mimeType.equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
