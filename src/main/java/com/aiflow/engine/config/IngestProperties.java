package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 文档解析配置（PDF / 图片 OCR）。
 *
 * <p>两件事分开看：**PDF 文本层提取不要钱、不要模型，永远开着**；**OCR 要调多模态模型（按页计费）**，
 * 所以它单独一个开关，默认关。这个区分很实际 —— 大部分 PDF 都有文本层，为它们打开一个「默认开着的
 * 付费开关」是不必要的风险暴露。
 *
 * @param maxBytes     单文件上限（解析要整体读进内存，先挡住超大文件）
 * @param maxPages     单文档最多解析多少页（防止一份 2000 页 PDF 把入库流程拖死）
 * @param ocr          OCR 子配置
 */
@ConfigurationProperties(prefix = "aiflow.ingest")
public record IngestProperties(
        @DefaultValue("20971520") long maxBytes,
        @DefaultValue("300") int maxPages,
        Ocr ocr
) {

    public IngestProperties {
        ocr = ocr == null ? new Ocr(false, 40, 10, 150) : ocr;
    }

    /**
     * @param enabled            扫描件/图片是否走 OCR（默认 false：会调多模态模型，按页计费）
     * @param minPageTextChars   一页文本层少于这个字数就当作扫描件（无文本层的 PDF 会返回空串）
     * @param maxPages           单文档最多 OCR 多少页（超过直接失败并说明，不做静默截断）
     * @param dpi                渲染扫描页为图片时的分辨率（150 够识别正文，再高只是更贵）
     */
    public record Ocr(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("40") int minPageTextChars,
            @DefaultValue("10") int maxPages,
            @DefaultValue("150") int dpi
    ) {
    }
}
