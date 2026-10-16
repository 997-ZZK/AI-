package com.aiflow.engine.ingest;

/**
 * 一页的文本（PDF 的第 n 页、图片的第 1 页）。
 *
 * <p>为什么切片要带页码：引用的可核验性是 RAG 的地基。章节路径（markdown 标题）解决「在哪一节」，
 * 页码解决「在 PDF 的哪一页」—— 后者是纸质/扫描件的唯一定位方式，也是人回去复核时最短的路径。
 */
public record PageText(int pageNumber, String text) {

    public PageText {
        text = text == null ? "" : text.strip();
    }

    public boolean blank() {
        return text.isBlank();
    }
}
