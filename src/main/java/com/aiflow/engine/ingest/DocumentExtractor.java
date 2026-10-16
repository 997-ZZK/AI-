package com.aiflow.engine.ingest;

import com.aiflow.engine.attachment.ImageKind;
import com.aiflow.engine.common.Hashes;
import com.aiflow.engine.config.IngestProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF / 图片 -> 文本：能白拿的文本层白拿，拿不到才花钱 OCR。
 *
 * <p>顺序是有意的：**先抽文本层，只有抽不出字（扫描件）的页才渲染成图片送去 OCR**。一份 50 页的电子
 * PDF 通常 0 页需要 OCR —— 反过来做（先渲染再判断）等于每页都花一次模型调用，还没有任何额外收益。
 *
 * <p>判据是「这一页的文本层字数 < {@code min-page-text-chars}」而不是「为空」：PDF 里常有一页只抽得出
 * 页眉页脚或几个孤立字符，那种残缺文本进知识库只会污染检索，不如按扫描页处理。
 *
 * <p>OCR 页数超上限**直接失败**，不做静默截断：一份 300 页的扫描件只入库前 10 页，用户以为整本可检索，
 * 这是比报错更坏的结果。要处理大文件就调大上限或拆分文件，这件事必须由人决定。
 */
public class DocumentExtractor {

    /**
     * PDFBox 会在首次用到字体时扫描系统字体并写一份缓存文件；不指定位置时它会往盘符根目录写
     * （Windows 上即 {@code C:\.pdfbox.cache}），普通权限下必然失败并在日志里留一段栈。
     * 缓存本身是可选的，把它指到临时目录即可：失败也只是下次重新扫，不影响解析结果。
     */
    static {
        if (System.getProperty("pdfbox.fontcache") == null) {
            System.setProperty("pdfbox.fontcache",
                    Path.of(System.getProperty("java.io.tmpdir"), "aiflow-pdfbox-fontcache").toString());
        }
    }

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractor.class);
    static final String PARSE_FAILED_MESSAGE = "文档解析失败：文件无法解析";
    static final String OCR_CALL_FAILED_MESSAGE = "OCR 调用失败：视觉识别请求未完成";
    private static final String OCR_DISABLED_HINT =
            "这份文件没有可用的文本层（扫描件或纯图片），需要打开 OCR 才能入库："
                    + "设 aiflow.ingest.ocr.enabled=true（或环境变量 AIFLOW_OCR_ENABLED=true），"
                    + "并确保有具备 vision 能力的模型档位";

    private final OcrProvider ocrProvider;
    private final IngestProperties properties;

    public DocumentExtractor(OcrProvider ocrProvider, IngestProperties properties) {
        this.ocrProvider = ocrProvider;
        this.properties = properties;
    }

    /** 支持 PDF 与图片（png / jpeg / webp / gif）；其他类型由调用方走纯文本解析。 */
    public ParsedDocument extract(DocumentSourceType type, String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (bytes.length > properties.maxBytes()) {
            throw new IllegalArgumentException("文件超过上限：" + bytes.length + " > " + properties.maxBytes() + " 字节");
        }
        List<PageText> pages = switch (type) {
            case PDF -> readPdf(fileName, bytes);
            case IMAGE -> List.of(new PageText(1, recognize(bytes, "图片")));
            default -> throw new IllegalArgumentException("不是 PDF 或图片类型: " + type);
        };
        return toDocument(type, fileName, pages);
    }

    private List<PageText> readPdf(String fileName, byte[] bytes) {
        PDDocument document;
        try {
            document = Loader.loadPDF(bytes);
        } catch (IOException | RuntimeException e) {
            throw parseFailure();
        }
        try (document) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new ValidationException("PDF 没有任何页面");
            }
            if (pageCount > properties.maxPages()) {
                throw new ValidationException("PDF 页数超过上限：" + pageCount + " > " + properties.maxPages()
                        + "（调大 aiflow.ingest.max-pages 或拆分文件）");
            }
            List<PageText> pages = new ArrayList<>(pageCount);
            List<Integer> scanned = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            try {
                for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                    stripper.setStartPage(pageNumber);
                    stripper.setEndPage(pageNumber);
                    String text = stripper.getText(document).strip();
                    if (text.length() < properties.ocr().minPageTextChars()) {
                        scanned.add(pageNumber);
                    }
                    pages.add(new PageText(pageNumber, text));
                }
                if (scanned.isEmpty()) {
                    log.info("PDF 文本层解析完成 pages={}（无需 OCR）", pageCount);
                    return pages;
                }
                if (!properties.ocr().enabled()) {
                    if (isMostlyBlank(pages)) {
                        throw OcrException.diagnostic(OcrException.Reason.DISABLED, OCR_DISABLED_HINT);
                    }
                    log.warn("PDF 有 {} 页没有文本层（共 {} 页），但 OCR 未启用，这些页的内容不会入库",
                            scanned.size(), pageCount);
                    return pages;
                }
                if (scanned.size() > properties.ocr().maxPages()) {
                    throw OcrException.diagnostic(OcrException.Reason.LIMIT_EXCEEDED,
                            "需要 OCR 的页数超过上限：" + scanned.size() + " > " + properties.ocr().maxPages()
                            + "（调大 aiflow.ingest.ocr.max-pages 或拆分文件；命中的页: " + preview(scanned) + "）");
                }
                log.info("PDF 需要 OCR 的页 {} 页 / 共 {} 页", scanned.size(), pageCount);
                return ocrPages(document, pages, scanned);
            } catch (OcrException e) {
                throw e.diagnosticCopy();
            } catch (IOException | RuntimeException e) {
                throw parseFailure();
            }
        } catch (ValidationException e) {
            // 重新创建异常，避免 try-with-resources 关闭失败时附加原始 suppressed 异常。
            throw new ValidationException(e.getMessage());
        } catch (OcrException e) {
            throw e.diagnosticCopy();
        } catch (IOException | RuntimeException e) {
            throw parseFailure();
        }
    }

    private List<PageText> ocrPages(PDDocument document, List<PageText> pages, List<Integer> scanned) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        List<PageText> result = new ArrayList<>(pages.size());
        for (PageText page : pages) {
            if (!scanned.contains(page.pageNumber())) {
                result.add(page);
                continue;
            }
            BufferedImage image = renderer.renderImageWithDPI(page.pageNumber() - 1, properties.ocr().dpi(),
                    ImageType.RGB);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", buffer)) {
                throw parseFailure();
            }
            String text = recognize(buffer.toByteArray(), "第 " + page.pageNumber() + " 页");
            // 文本层那点残字与 OCR 结果取长者：有页眉信息也留着，不给下游造成「这页什么都没有」的错觉
            String merged = text.length() >= page.text().length() ? text : page.text();
            result.add(new PageText(page.pageNumber(), merged));
        }
        return result;
    }

    private String recognize(byte[] imageBytes, String hint) {
        String mimeType = mimeTypeOf(imageBytes);
        if (!properties.ocr().enabled()) {
            throw OcrException.diagnostic(OcrException.Reason.DISABLED, OCR_DISABLED_HINT);
        }
        boolean available;
        try {
            available = ocrProvider.available();
        } catch (RuntimeException e) {
            throw OcrException.callFailed();
        }
        if (!available) {
            throw OcrException.unavailable();
        }
        String text;
        try {
            text = ocrProvider.recognize(imageBytes, mimeType);
        } catch (OcrException e) {
            if (e.reason() == OcrException.Reason.UNAVAILABLE) {
                throw OcrException.unavailable();
            }
            throw OcrException.callFailed();
        } catch (RuntimeException e) {
            throw OcrException.callFailed();
        }
        if (text == null || text.isBlank()) {
            throw OcrException.diagnostic(OcrException.Reason.NO_TEXT, "OCR 没有识别出任何文字（" + hint + "）；"
                    + "如果这是纯图形文件，请改用文字版或提高扫描质量");
        }
        return text.strip();
    }

    private static String mimeTypeOf(byte[] bytes) {
        return ImageKind.sniff(bytes).map(ImageKind::mimeType).orElseThrow(DocumentExtractor::parseFailure);
    }

    private static boolean isMostlyBlank(List<PageText> pages) {
        return pages.stream().allMatch(PageText::blank);
    }

    private static String preview(List<Integer> pageNumbers) {
        String joined = pageNumbers.stream().limit(8).map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        return pageNumbers.size() <= 8 ? joined : joined + "…";
    }

    private ParsedDocument toDocument(DocumentSourceType type, String fileName, List<PageText> pages) {
        String content = pages.stream().map(PageText::text).filter(text -> !text.isBlank())
                .reduce((a, b) -> a + "\n\n" + b).orElse("");
        if (content.isBlank()) {
            throw parseFailure();
        }
        return new ParsedDocument(fileName, type, content, Hashes.sha256Hex(content), pages);
    }

    private static IllegalArgumentException parseFailure() {
        return new IllegalArgumentException(PARSE_FAILED_MESSAGE);
    }

    private static final class ValidationException extends IllegalArgumentException {
        private ValidationException(String message) {
            super(message);
        }
    }
}
