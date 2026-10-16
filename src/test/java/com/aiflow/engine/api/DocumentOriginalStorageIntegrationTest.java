package com.aiflow.engine.api;

import com.aiflow.engine.persistence.repo.DocumentRepository;
import com.aiflow.engine.storage.ObjectStorage;
import com.aiflow.engine.storage.StorageException;
import com.aiflow.engine.storage.StoredObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 原件留存（对象存储）的接口级验证：存了什么、取回来是否一字不差、"存不上"时入库还成不成功。
 *
 * <p>用内存桩替掉真实 MinIO：这里要证的是**链路与语义**（键怎么起、失败怎么降级、没有原件时接口给什么口径），
 * 真实 MinIO 的连通性属于联调范围（README 有实测记录）。
 *
 * <p>特别钉住一条：**留存失败不能让入库失败**。对象存储是可选依赖，它挂了还让用户连知识库都用不了，
 * 那是把旁路能力做成了硬依赖 —— 这条得靠测试守住。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentOriginalStorageIntegrationTest {

    private static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();

    private static final AtomicBoolean FAIL_PUTS = new AtomicBoolean();

    private static final AtomicBoolean FAIL_STATS = new AtomicBoolean();

    private static final AtomicBoolean FAIL_OPENS = new AtomicBoolean();

    private static final AtomicBoolean OPEN_NOT_FOUND = new AtomicBoolean();

    private static final AtomicBoolean ENABLED = new AtomicBoolean(true);

    private static final AtomicBoolean FAIL_ENABLED = new AtomicBoolean();

    private static final AtomicReference<String> STREAM_FAILURE = new AtomicReference<>();

    private static final AtomicReference<String> CONTENT_TYPE = new AtomicReference<>("application/pdf");

    private static final AtomicReference<Long> STAT_SIZE = new AtomicReference<>();

    private static final AtomicInteger OPEN_CALLS = new AtomicInteger();

    private static final String SENSITIVE = "provider-body-credential-url-sensitive";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DocumentController controller;

    @Autowired
    private DocumentRepository documentRepository;

    @BeforeEach
    void resetStore() {
        OBJECTS.clear();
        FAIL_PUTS.set(false);
        FAIL_STATS.set(false);
        FAIL_OPENS.set(false);
        OPEN_NOT_FOUND.set(false);
        ENABLED.set(true);
        FAIL_ENABLED.set(false);
        STREAM_FAILURE.set(null);
        CONTENT_TYPE.set("application/pdf");
        STAT_SIZE.set(null);
        OPEN_CALLS.set(0);
    }

    @TestConfiguration
    static class StubStorageConfig {

        @Bean
        @Primary
        ObjectStorage stubObjectStorage() {
            return new InMemoryObjectStorage();
        }
    }

    @Test
    void storesOriginalAndServesItBackByteForByte() throws Exception {
        String kbId = "it-original-" + UUID.randomUUID();
        byte[] pdf = textPdf("Travel policy 2026: hotel cap per night is 500 CNY.");

        JsonNode ingested = upload(kbId, new MockMultipartFile("file", "政策/2026 差旅.pdf", "application/pdf", pdf));

        String documentId = ingested.path("documentId").asText();
        String objectKey = ingested.path("objectKey").asText();
        assertThat(objectKey)
                .as("键按 kb / document 分层，文件名里的路径分隔符必须被净化掉")
                .isEqualTo("kb/" + kbId + "/documents/" + documentId + "/政策_2026 差旅.pdf");
        assertThat(ingested.path("objectBytes").asLong()).isEqualTo(pdf.length);
        assertThat(OBJECTS.get(objectKey)).isEqualTo(pdf);

        // 列表接口也要能看到原件信息（有没有原件不用去翻日志）
        JsonNode documents = getData("/api/v1/kbs/" + kbId + "/documents");
        assertThat(documents.get(0).path("objectKey").asText()).isEqualTo(objectKey);
        assertThat(documents.get(0).path("objectBytes").asLong()).isEqualTo(pdf.length);

        MvcResult download = mockMvc.perform(get("/api/v1/kbs/" + kbId + "/documents/" + documentId + "/original"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray())
                .as("下载到的必须与上传的原件一字不差")
                .isEqualTo(pdf);
        assertThat(download.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(download.getResponse().getContentLength()).isEqualTo(pdf.length);
        assertThat(download.getResponse().getHeader("X-Object-Key")).isEqualTo(objectKey);
    }

    @Test
    void ingestionStillSucceedsWhenStorageIsDown() throws Exception {
        FAIL_PUTS.set(true);
        String kbId = "it-original-fail-" + UUID.randomUUID();
        byte[] pdf = textPdf("Storage is down but the document must still become searchable.");

        JsonNode ingested = upload(kbId, new MockMultipartFile("file", "policy.pdf", "application/pdf", pdf));

        assertThat(ingested.path("status").asText()).isEqualTo("INDEXED");
        assertThat(ingested.path("chunkCount").asInt()).isGreaterThan(0);
        assertThat(ingested.path("objectKey").isNull())
                .as("留存失败时键为 null —— 响应里直接可见，不用去翻 WARN 日志")
                .isTrue();

        mockMvc.perform(get("/api/v1/kbs/" + kbId + "/documents/" + ingested.path("documentId").asText() + "/original"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("没有留存原件"));
    }

    @Test
    void textIngestHasNoOriginal() throws Exception {
        String kbId = "it-original-text-" + UUID.randomUUID();
        JsonNode ingested = unwrap(mockMvc.perform(post("/api/v1/kbs/" + kbId + "/documents")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "粘贴进来的文本", "content", "纯文本入库没有原件，只有切片。"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(ingested.path("chunkCount").asInt()).isGreaterThan(0);
        assertThat(ingested.path("objectKey").isNull()).isTrue();
        mockMvc.perform(get("/api/v1/kbs/" + kbId + "/documents/" + ingested.path("documentId").asText() + "/original"))
                .andExpect(status().isNotFound());
    }

    @Test
    void storageStatFailureUsesFixed503WithoutProviderDetails() throws Exception {
        UploadedOriginal uploaded = uploadOriginal("it-original-stat-failure");
        FAIL_STATS.set(true);

        MvcResult result = mockMvc.perform(get(uploaded.path()))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("对象存储元信息查询失败").doesNotContain(SENSITIVE).doesNotContain(uploaded.objectKey());
    }

    @Test
    void storageOpenFailureUsesFixed503WithoutProviderDetails() throws Exception {
        UploadedOriginal uploaded = uploadOriginal("it-original-open-failure");
        FAIL_OPENS.set(true);

        MvcResult result = mockMvc.perform(get(uploaded.path()))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("对象存储读取失败").doesNotContain(SENSITIVE).doesNotContain(uploaded.objectKey());
    }

    @Test
    void objectRemovedBetweenStatAndOpenIs404WithoutProviderDetails() throws Exception {
        UploadedOriginal uploaded = uploadOriginal("it-original-race");
        OPEN_NOT_FOUND.set(true);

        MvcResult result = mockMvc.perform(get(uploaded.path()))
                .andExpect(status().isNotFound())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("原件已不在对象存储里").doesNotContain(SENSITIVE).doesNotContain(uploaded.objectKey());
    }

    @Test
    void disabledStorageReturns503EvenForPreviouslyStoredObject() throws Exception {
        UploadedOriginal uploaded = uploadOriginal("it-disabled");
        ENABLED.set(false);

        MvcResult result = mockMvc.perform(get(uploaded.path()))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("原件留存未开启").doesNotContain(SENSITIVE).doesNotContain(uploaded.objectKey());
    }

    @Test
    void storageEnabledFailureUsesFixed503WithoutProviderDetails() throws Exception {
        UploadedOriginal uploaded = uploadOriginal("it-enabled-fail");
        FAIL_ENABLED.set(true);

        MvcResult result = mockMvc.perform(get(uploaded.path()))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("对象存储不可用").doesNotContain(SENSITIVE).doesNotContain(uploaded.objectKey());
    }

    @Test
    void lateStreamReadSkipAndCloseFailuresHaveNoProviderMessageOrExceptionChain() throws Exception {
        UploadedOriginal uploaded = uploadOriginal("it-stream-fail");
        for (String operation : new String[]{"read", "skip", "close"}) {
            STREAM_FAILURE.set(operation);
            InputStream stream = controller.original(uploaded.kbId(), uploaded.documentId())
                    .getBody().getInputStream();
            assertThatThrownBy(() -> {
                switch (operation) {
                    case "read" -> stream.read();
                    case "skip" -> stream.skip(1);
                    case "close" -> stream.close();
                    default -> throw new AssertionError("unknown stream test operation");
                }
            }).isInstanceOf(IOException.class).hasMessage("对象存储读取失败")
                    .hasNoCause().satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
        }
    }

    @Test
    void oldObjectKeysCannotInjectControlCharactersIntoResponseHeaders() throws Exception {
        UploadedOriginal uploaded = uploadOriginal("it-header");
        String oldKey = uploaded.objectKey().replace("policy.pdf", "old\r\nheader\\name.pdf");
        byte[] bytes = OBJECTS.remove(uploaded.objectKey());
        OBJECTS.put(oldKey, bytes);
        var document = documentRepository.findById(uploaded.documentId()).orElseThrow();
        document.markObjectStored(oldKey, bytes.length, Instant.now());
        documentRepository.save(document);

        MvcResult result = mockMvc.perform(get(uploaded.path())).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("X-Object-Key")).doesNotContain("\r", "\n");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .doesNotContain("\r", "\n", "\\").contains("old__header_name.pdf");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void invalidMetadataMimeFallsBackToBinaryWithoutLoggingOriginalValue(CapturedOutput output) throws Exception {
        UploadedOriginal uploaded = uploadOriginal("it-mime");
        byte[] expected = OBJECTS.get(uploaded.objectKey());
        for (String contentType : new String[]{"invalid mime " + SENSITIVE, "application/pdf\r\n" + SENSITIVE}) {
            CONTENT_TYPE.set(contentType);
            MvcResult result = mockMvc.perform(get(uploaded.path())).andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getContentType()).isEqualTo("application/octet-stream");
            assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(expected);
            assertThat(result.getResponse().getHeaderNames().stream()
                    .map(name -> result.getResponse().getHeaders(name)).toList().toString())
                    .doesNotContain(SENSITIVE, "\r", "\n");
        }
        assertThat(output.getAll()).doesNotContain(SENSITIVE);
    }

    @Test
    void metadataMimeParametersAreDiscarded() throws Exception {
        UploadedOriginal uploaded = uploadOriginal("it-mime-params");
        CONTENT_TYPE.set("application/pdf; charset=UTF-8; provider=" + SENSITIVE);

        MvcResult result = mockMvc.perform(get(uploaded.path())).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
    }

    @Test
    void negativeMetadataSizeReturnsFixed503BeforeOpeningObject() throws Exception {
        UploadedOriginal uploaded = uploadOriginal("it-size");
        STAT_SIZE.set(-1L);

        MvcResult result = mockMvc.perform(get(uploaded.path()))
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("对象存储元信息查询失败").doesNotContain(SENSITIVE, uploaded.objectKey());
        assertThat(OPEN_CALLS.get()).isZero();
    }

    /** 内存版对象存储：够用来验链路，且能一键切成"写不进去"。 */
    private static final class InMemoryObjectStorage implements ObjectStorage {

        @Override
        public boolean enabled() {
            if (FAIL_ENABLED.get()) {
                throw sensitiveFailure();
            }
            return ENABLED.get();
        }

        @Override
        public String id() {
            return "in-memory";
        }

        @Override
        public StoredObject put(String key, byte[] bytes, String contentType) {
            if (FAIL_PUTS.get()) {
                throw new StorageException("对象存储不可用（测试故意让它失败）");
            }
            OBJECTS.put(key, bytes.clone());
            return new StoredObject(key, bytes.length, contentType, "etag-" + OBJECTS.size());
        }

        @Override
        public Optional<StoredObject> stat(String key) {
            if (FAIL_STATS.get()) {
                throw sensitiveFailure();
            }
            byte[] bytes = OBJECTS.get(key);
            return bytes == null ? Optional.empty() : Optional.of(new StoredObject(key,
                    STAT_SIZE.get() == null ? bytes.length : STAT_SIZE.get(), CONTENT_TYPE.get(), "etag"));
        }

        @Override
        public InputStream open(String key) {
            OPEN_CALLS.incrementAndGet();
            if (OPEN_NOT_FOUND.get()) {
                throw StorageException.objectNotFound();
            }
            if (FAIL_OPENS.get()) {
                throw sensitiveFailure();
            }
            byte[] bytes = OBJECTS.get(key);
            if (bytes == null) {
                throw new StorageException("对象不存在: " + key);
            }
            if (STREAM_FAILURE.get() != null) {
                return new ByteArrayInputStream(bytes) {
                    @Override
                    public synchronized int read() {
                        if ("read".equals(STREAM_FAILURE.get())) {
                            throw sensitiveFailure();
                        }
                        return super.read();
                    }

                    @Override
                    public synchronized long skip(long count) {
                        if ("skip".equals(STREAM_FAILURE.get())) {
                            throw sensitiveFailure();
                        }
                        return super.skip(count);
                    }

                    @Override
                    public void close() throws IOException {
                        if ("close".equals(STREAM_FAILURE.get())) {
                            IOException failure = new IOException(SENSITIVE, sensitiveFailure());
                            failure.addSuppressed(sensitiveFailure());
                            throw failure;
                        }
                        super.close();
                    }
                };
            }
            return new ByteArrayInputStream(bytes);
        }
    }

    private static RuntimeException sensitiveFailure() {
        RuntimeException failure = new IllegalStateException(SENSITIVE, new RuntimeException(SENSITIVE));
        failure.addSuppressed(new RuntimeException(SENSITIVE));
        return failure;
    }

    private JsonNode upload(String kbId, MockMultipartFile file) throws Exception {
        return unwrap(mockMvc.perform(multipart("/api/v1/kbs/" + kbId + "/documents/upload").file(file))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private UploadedOriginal uploadOriginal(String prefix) throws Exception {
        String kbId = prefix + "-" + UUID.randomUUID();
        JsonNode ingested = upload(kbId, new MockMultipartFile("file", "policy.pdf", "application/pdf",
                textPdf("Original storage boundary test.")));
        String documentId = ingested.path("documentId").asText();
        return new UploadedOriginal("/api/v1/kbs/" + kbId + "/documents/" + documentId + "/original",
                ingested.path("objectKey").asText(), kbId, UUID.fromString(documentId));
    }

    private record UploadedOriginal(String path, String objectKey, String kbId, UUID documentId) {
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
                stream.newLineAtOffset(72, 700);
                stream.showText(text);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
