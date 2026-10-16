package com.aiflow.engine.ingest;

import com.aiflow.engine.config.IngestProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * PDF / 图片 -> 文本：文本层优先、扫描页才 OCR、OCR 开关与页数闸的行为。
 *
 * <p>这些用例全部离线：OCR 用桩，不碰真实模型。真实档位的验证放在联调（见 README「PDF 与图片入库」），
 * 因为那件事的本质是「模型认不认得出字」，而不是「我们的流程接没接对」。
 */
class DocumentExtractorTest {

    /** 桩 OCR：记录被调了几次，返回固定文本。 */
    private static final class StubOcr implements OcrProvider {

        private final List<Integer> sizes = new ArrayList<>();
        private boolean available = true;
        private String text = "扫描页识别出的内容：差旅住宿标准与报销材料清单。";

        @Override
        public String id() {
            return "stub-ocr";
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String recognize(byte[] imageBytes, String mimeType) {
            sizes.add(imageBytes.length);
            assertThat(mimeType).isNotBlank();
            return text;
        }

        int calls() {
            return sizes.size();
        }
    }

    private static IngestProperties properties(boolean ocrEnabled, int minChars, int maxOcrPages) {
        return new IngestProperties(20_971_520L, 300,
                new IngestProperties.Ocr(ocrEnabled, minChars, maxOcrPages, 150));
    }

    /** 有文本层的 PDF：每页一段 ASCII 文本（测试里不用中文字体，避免字体缺失带来的不确定性）。 */
    private static byte[] textPdf(String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            return save(document);
        }
    }

    /** 扫描件：页面上只有一张位图，没有文本层（模拟扫描出来的 PDF）。 */
    private static byte[] scannedPdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 200, 100);
            graphics.setColor(Color.BLACK);
            graphics.drawString("SCAN", 60, 50);
            graphics.dispose();
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.drawImage(xObject, 100, 600, 200, 100);
                }
            }
            return save(document);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        document.save(buffer);
        return buffer.toByteArray();
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 200, 100);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        graphics.drawString("ATTENDANCE", 20, 55);
        graphics.dispose();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    @Test
    void readsTextLayerWithoutCallingOcr() throws IOException {
        StubOcr ocr = new StubOcr();
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 10));

        ParsedDocument parsed = extractor.extract(DocumentSourceType.PDF, "policy.pdf",
                textPdf("Reimbursement policy: invoices must carry the company name.",
                        "Travel standard: 500 CNY per night in tier-1 cities."));

        assertThat(parsed.pages()).hasSize(2);
        assertThat(parsed.pages().get(0).pageNumber()).isEqualTo(1);
        assertThat(parsed.pages().get(1).text()).contains("Travel standard");
        assertThat(parsed.type()).isEqualTo(DocumentSourceType.PDF);
        assertThat(parsed.contentHash()).isNotBlank();
        // 关键断言：有文本层就不该花 OCR 的钱
        assertThat(ocr.calls()).isZero();
    }

    @Test
    void fallsBackToOcrForScannedPagesOnly() throws IOException {
        StubOcr ocr = new StubOcr();
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 10));

        ParsedDocument parsed = extractor.extract(DocumentSourceType.PDF, "mixed.pdf",
                scannedPdf(2));

        assertThat(ocr.calls()).isEqualTo(2);
        assertThat(parsed.pages()).hasSize(2);
        assertThat(parsed.pages()).allSatisfy(page -> assertThat(page.text()).contains("扫描页识别出"));
        assertThat(parsed.content()).contains("差旅住宿标准");
    }

    @Test
    void rejectsScannedPdfWhenOcrDisabled() throws IOException {
        StubOcr ocr = new StubOcr();
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(false, 40, 10));

        assertThatThrownBy(() -> extractor.extract(DocumentSourceType.PDF, "scan.pdf", scannedPdf(1)))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("AIFLOW_OCR_ENABLED")
                .satisfies(error -> assertThat(((OcrException) error).reason()).isEqualTo(OcrException.Reason.DISABLED));
        assertThat(ocr.calls()).isZero();
    }

    @Test
    void keepsTextLayerPdfWhenOcrDisabled() throws IOException {
        StubOcr ocr = new StubOcr();
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(false, 40, 10));

        ParsedDocument parsed = extractor.extract(DocumentSourceType.PDF, "digital.pdf",
                textPdf("Hello digital PDF with a proper text layer."));

        assertThat(parsed.content()).contains("digital PDF");
        assertThat(ocr.calls()).isZero();
    }

    @Test
    void failsWhenScannedPagesExceedOcrLimit() throws IOException {
        StubOcr ocr = new StubOcr();
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 2));

        assertThatThrownBy(() -> extractor.extract(DocumentSourceType.PDF, "big-scan.pdf", scannedPdf(3)))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("max-pages")
                .satisfies(error -> assertThat(((OcrException) error).reason()).isEqualTo(OcrException.Reason.LIMIT_EXCEEDED));
        assertThat(ocr.calls()).isZero();
    }

    @Test
    void failsWithActionableMessageWhenNoVisionTier() throws IOException {
        StubOcr ocr = new StubOcr();
        ocr.available = false;
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 10));

        assertThatThrownBy(() -> extractor.extract(DocumentSourceType.PDF, "scan.pdf", scannedPdf(1)))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("vision")
                .satisfies(error -> assertThat(((OcrException) error).reason()).isEqualTo(OcrException.Reason.UNAVAILABLE));
    }

    @Test
    void recognizesImageDocumentAsSinglePage() throws IOException {
        StubOcr ocr = new StubOcr();
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 10));

        ParsedDocument parsed = extractor.extract(DocumentSourceType.IMAGE, "attendance.png", png());

        assertThat(ocr.calls()).isEqualTo(1);
        assertThat(parsed.type()).isEqualTo(DocumentSourceType.IMAGE);
        assertThat(parsed.pages()).hasSize(1);
        assertThat(parsed.pages().get(0).pageNumber()).isEqualTo(1);
    }

    @Test
    void failsOnEmptyOcrResultInsteadOfIndexingNothing() throws IOException {
        StubOcr ocr = new StubOcr();
        ocr.text = "   ";
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 10));

        assertThatThrownBy(() -> extractor.extract(DocumentSourceType.IMAGE, "blank.png", png()))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("没有识别出任何文字");
    }

    @Test
    void rejectsOversizedFileBeforeParsing() throws IOException {
        StubOcr ocr = new StubOcr();
        IngestProperties tiny = new IngestProperties(100L, 300, new IngestProperties.Ocr(true, 40, 10, 150));
        DocumentExtractor extractor = new DocumentExtractor(ocr, tiny);

        assertThatThrownBy(() -> extractor.extract(DocumentSourceType.PDF, "big.pdf", textPdf("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过上限");
    }

    @Test
    void fixesMalformedPdfParserMessageAndDropsExceptionChain() {
        StubOcr ocr = new StubOcr();
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 10));

        Throwable failure = catchThrowable(() -> extractor.extract(DocumentSourceType.PDF,
                "parser-secret\r\n.pdf", "PDFBOX_SECRET".getBytes()));

        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        assertThat(failure).hasMessage(DocumentExtractor.PARSE_FAILED_MESSAGE);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage()).doesNotContain("PDFBOX_SECRET", "parser-secret");
    }

    @Test
    void fixesCustomOcrAvailabilityFailureAndDropsExceptionChain() throws IOException {
        OcrProvider ocr = new OcrProvider() {
            @Override
            public String id() {
                return "custom-ocr";
            }

            @Override
            public boolean available() {
                throw new IllegalStateException("OCR_PROVIDER_SECRET", new IOException("nested-secret"));
            }

            @Override
            public String recognize(byte[] imageBytes, String mimeType) {
                throw new AssertionError("recognize should not be called");
            }
        };
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 10));

        Throwable failure = catchThrowable(() -> extractor.extract(DocumentSourceType.PDF, "scan.pdf", scannedPdf(1)));

        assertThat(failure).isInstanceOf(OcrException.class);
        assertThat(failure).hasMessage(DocumentExtractor.OCR_CALL_FAILED_MESSAGE);
        assertThat(((OcrException) failure).reason()).isEqualTo(OcrException.Reason.CALL_FAILED);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage()).doesNotContain("OCR_PROVIDER_SECRET", "nested-secret");
    }

    @Test
    void fixesCustomOcrRecognitionFailureAndDropsExceptionChain() throws IOException {
        OcrProvider ocr = new OcrProvider() {
            @Override
            public String id() {
                return "custom-ocr";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String recognize(byte[] imageBytes, String mimeType) {
                throw new IllegalStateException("OCR_PROVIDER_SECRET", new IOException("nested-secret"));
            }
        };
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 10));

        Throwable failure = catchThrowable(() -> extractor.extract(DocumentSourceType.PDF, "scan.pdf", scannedPdf(1)));

        assertThat(failure).isInstanceOf(OcrException.class);
        assertThat(failure).hasMessage(DocumentExtractor.OCR_CALL_FAILED_MESSAGE);
        assertThat(((OcrException) failure).reason()).isEqualTo(OcrException.Reason.CALL_FAILED);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage()).doesNotContain("OCR_PROVIDER_SECRET", "nested-secret");
    }

    @Test
    void rejectsImageWithInvalidFileHeaderBeforeCallingOcr() {
        StubOcr ocr = new StubOcr();
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 10));

        Throwable failure = catchThrowable(() -> extractor.extract(DocumentSourceType.IMAGE, "fake.png",
                "not-an-image".getBytes()));

        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        assertThat(failure).hasMessage(DocumentExtractor.PARSE_FAILED_MESSAGE);
        assertThat(ocr.calls()).isZero();
    }

    @Test
    void fixesCustomOcrExceptionWithoutInspectingMessage() throws IOException {
        OcrProvider ocr = new OcrProvider() {
            @Override
            public String id() {
                throw new AssertionError("provider id is not needed for parsing");
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String recognize(byte[] imageBytes, String mimeType) {
                OcrException failure = new OcrException("OCR_PROVIDER_SECRET", new IOException("cause-secret")) {
                    @Override
                    public String getMessage() {
                        throw new IllegalStateException("message-secret");
                    }
                };
                failure.addSuppressed(new IllegalStateException("suppressed-secret"));
                throw failure;
            }
        };
        DocumentExtractor extractor = new DocumentExtractor(ocr, properties(true, 40, 10));

        Throwable failure = catchThrowable(() -> extractor.extract(DocumentSourceType.PDF, "scan.pdf", scannedPdf(1)));

        assertThat(failure).isInstanceOf(OcrException.class);
        assertThat(failure).hasMessage(DocumentExtractor.OCR_CALL_FAILED_MESSAGE);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    @Test
    void keepsEmptyPageValidationWithoutFileName() throws IOException {
        DocumentExtractor extractor = new DocumentExtractor(new StubOcr(), properties(true, 40, 10));
        byte[] emptyPdf;
        try (PDDocument document = new PDDocument()) {
            emptyPdf = save(document);
        }

        assertThatThrownBy(() -> extractor.extract(DocumentSourceType.PDF, "file-name-secret.pdf", emptyPdf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PDF 没有任何页面");
    }
}
