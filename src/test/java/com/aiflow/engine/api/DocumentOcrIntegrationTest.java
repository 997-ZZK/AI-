package com.aiflow.engine.api;

import com.aiflow.engine.ingest.OcrProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PDF 与图片入库的接口级验证（H2 + memory 向量库 + 桩 OCR，全程离线）。
 *
 * <p>桩 OCR 用 {@code @Primary} 顶掉真实的 {@code VisionOcrProvider}：这里要证的链是
 * 「上传 -> 分诊 -> 文本层/识别 -> 按页切片 -> 落库可查」，模型认字的能力属于联调范围（README 有记录）。
 * 顺带覆盖一条容易漏的行为：**扫码件走 OCR 时，切片的页码必须带到接口返回上**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentOcrIntegrationTest {

    private static final AtomicInteger OCR_CALLS = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void ingestProperties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.ingest.ocr.enabled", () -> "true");
        registry.add("aiflow.ingest.ocr.min-page-text-chars", () -> "40");
        registry.add("aiflow.ingest.ocr.max-pages", () -> "5");
    }

    @TestConfiguration
    static class StubOcrConfig {

        @Bean
        @Primary
        OcrProvider stubOcrProvider() {
            return new OcrProvider() {
                @Override
                public String id() {
                    return "stub-ocr";
                }

                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public String recognize(byte[] imageBytes, String mimeType) {
                    OCR_CALLS.incrementAndGet();
                    return "识别结果：扫描页正文，含差旅住宿标准与报销材料清单，用于验证 OCR 入库链路。";
                }
            };
        }
    }

    @Test
    void ingestsPdfTextLayerWithPageNumbers() throws Exception {
        int before = OCR_CALLS.get();
        String kbId = "it-pdf-" + UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf",
                textPdf("Invoice must carry the company full name and tax number."));

        JsonNode ingested = upload(kbId, file);

        assertThat(ingested.path("chunkCount").asInt()).isGreaterThan(0);
        assertThat(ingested.path("status").asText()).isEqualTo("INDEXED");

        JsonNode documents = getData("/api/v1/kbs/" + kbId + "/documents");
        assertThat(documents.get(0).path("sourceType").asText()).isEqualTo("PDF");

        JsonNode chunks = getData("/api/v1/kbs/" + kbId + "/documents/"
                + documents.get(0).path("documentId").asText() + "/chunks");
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).path("pageFrom").asInt()).isEqualTo(1);
        assertThat(chunks.get(0).path("preview").asText()).contains("Invoice");
        // 有文本层就不该花 OCR 的钱：用增量断言，避免用例执行顺序影响结果
        assertThat(OCR_CALLS.get() - before).isZero();
    }

    @Test
    void ingestsScannedPdfThroughOcr() throws Exception {
        int before = OCR_CALLS.get();
        String kbId = "it-scan-" + UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "scan.pdf", "application/pdf", scannedPdf(2));

        JsonNode ingested = upload(kbId, file);

        assertThat(ingested.path("chunkCount").asInt()).isGreaterThan(0);
        assertThat(OCR_CALLS.get() - before).isEqualTo(2);

        JsonNode chunks = getData("/api/v1/kbs/" + kbId + "/documents/"
                + ingested.path("documentId").asText() + "/chunks");
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.path("pageFrom").asInt()).isEqualTo(1));
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.path("pageFrom").asInt()).isEqualTo(2));
    }

    @Test
    void ingestsImageThroughOcr() throws Exception {
        int before = OCR_CALLS.get();
        String kbId = "it-img-" + UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "attendance.png", "image/png", png());

        JsonNode ingested = upload(kbId, file);

        assertThat(ingested.path("chunkCount").asInt()).isGreaterThan(0);
        assertThat(OCR_CALLS.get() - before).isEqualTo(1);

        JsonNode documents = getData("/api/v1/kbs/" + kbId + "/documents");
        assertThat(documents.get(0).path("sourceType").asText()).isEqualTo("IMAGE");
        assertThat(documents.get(0).path("title").asText()).isEqualTo("attendance.png");
    }

    @Test
    void rejectsBinaryOfficeFormatsWithActionableMessage() throws Exception {
        String kbId = "it-docx-" + UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "PK\u0003\u0004binary".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/kbs/" + kbId + "/documents/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("需要先转成文本"));
    }

    private JsonNode upload(String kbId, MockMultipartFile file) throws Exception {
        return unwrap(mockMvc.perform(multipart("/api/v1/kbs/" + kbId + "/documents/upload").file(file))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getData(String path) throws Exception {
        return unwrap(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode unwrap(String body) throws Exception {
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }

    private static byte[] textPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            return save(document);
        }
    }

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
                var xObject = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, image);
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
}
