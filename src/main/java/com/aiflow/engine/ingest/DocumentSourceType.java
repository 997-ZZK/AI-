package com.aiflow.engine.ingest;

public enum DocumentSourceType {
    TEXT,
    MARKDOWN,
    JSON,
    /** PDF：优先抽文本层，扫描页在 OCR 打开时渲染成图片识别（见 {@link DocumentExtractor}）。 */
    PDF,
    /** 图片：本身没有文本层，只能走 OCR。 */
    IMAGE
}
