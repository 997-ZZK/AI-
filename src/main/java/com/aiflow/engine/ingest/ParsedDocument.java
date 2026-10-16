package com.aiflow.engine.ingest;

import java.util.List;

/**
 * 解析结果：整体文本 + （可选）逐页文本。
 *
 * <p>{@code pages} 为空 = 这份文档没有页的概念（text / markdown / json）；非空 = PDF 或图片，
 * 切片要按页归属（引用的可核验性见 {@link PageText}）。
 */
public record ParsedDocument(
        String title,
        DocumentSourceType type,
        String content,
        String contentHash,
        List<PageText> pages
) {

    public ParsedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** 无页概念的文档（text / markdown / json）用的便捷构造。 */
    public ParsedDocument(String title, DocumentSourceType type, String content, String contentHash) {
        this(title, type, content, contentHash, List.of());
    }
}
