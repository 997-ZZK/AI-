package com.aiflow.engine.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aiflow.engine.api.service.DocumentIngestService;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.common.exception.GlobalExceptionHandler;
import com.aiflow.engine.ingest.DocumentExtractor;
import com.aiflow.engine.ingest.DocumentParser;
import com.aiflow.engine.ingest.TextChunker;
import com.aiflow.engine.persistence.entity.DocumentEntity;
import com.aiflow.engine.persistence.repo.DocumentChunkRepository;
import com.aiflow.engine.persistence.repo.DocumentRepository;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.EmbeddingStore;
import com.aiflow.engine.storage.ObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 用真实解析/入库服务与离线端口桩验证错误在 API、失败记录和日志中的边界。 */
class DocumentIngestErrorBoundaryTest {

    private static final String BASE = "/api/v1/kbs/boundary-documents/documents";
    private static final String BODY_MARKER = "synthetic-document-private-body";
    private static final String CAUSE_MARKER = "synthetic-document-private-cause";
    private static final String SUPPRESSED_MARKER = "synthetic-document-private-suppressed";

    private final ObjectMapper mapper = new ObjectMapper();
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final EmbeddingProvider embedding = mock(EmbeddingProvider.class);
    private final EmbeddingStore vectors = mock(EmbeddingStore.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final AtomicReference<DocumentEntity> saved = new AtomicReference<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> observedLoggers = List.of(
            (Logger) LoggerFactory.getLogger(DocumentIngestService.class),
            (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(documents.findByKbIdAndContentHash(anyString(), anyString())).thenReturn(Optional.empty());
        when(documents.save(any(DocumentEntity.class))).thenAnswer(invocation -> {
            DocumentEntity document = invocation.getArgument(0);
            saved.set(document);
            return document;
        });
        when(documents.findByKbIdOrderByCreatedAtDesc(anyString()))
                .thenAnswer(ignored -> saved.get() == null ? List.of() : List.of(saved.get()));
        when(embedding.id()).thenReturn("synthetic-embedding");
        when(embedding.dimensions()).thenReturn(4);
        when(embedding.embedAll(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(ignored -> new float[]{1f, 0f, 0f, 0f}).toList();
        });
        DocumentIngestService service = new DocumentIngestService(documents, chunks,
                new DocumentParser(mapper), mock(DocumentExtractor.class), new TextChunker(),
                embedding, vectors, storage);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(service, documents, chunks, storage))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        logs.start();
        observedLoggers.forEach(logger -> logger.addAppender(logs));
    }

    @AfterEach
    void stopLogging() {
        observedLoggers.forEach(logger -> logger.detachAppender(logs));
        logs.stop();
    }

    @Test
    void multipartReadFailureReturnsFixed400WithoutExceptionDetails() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getName()).thenReturn("file");
        when(file.getOriginalFilename()).thenReturn("policy.txt");
        IOException failure = new IOException(BODY_MARKER, new IllegalStateException(CAUSE_MARKER));
        failure.addSuppressed(new IllegalStateException(SUPPRESSED_MARKER));
        when(file.getBytes()).thenThrow(failure);

        MvcResult result = mvc.perform(multipart(BASE + "/upload").with(request -> {
                    ((MockMultipartHttpServletRequest) request).addFile(file);
                    return request;
                }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("文件读取失败"))
                .andReturn();

        assertSanitizedFailure(result);
        verify(documents, never()).save(any(DocumentEntity.class));
    }

    @Test
    void invalidJsonContentReturnsFixed400WithoutEchoingTheContent() throws Exception {
        MvcResult result = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("title", "policy.json", "sourceType", "JSON",
                                "content", "{\"private\":\"" + BODY_MARKER + "\", broken"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("JSON 解析失败：内容不是合法 JSON"))
                .andReturn();

        assertSanitizedFailure(result);
        verify(documents, never()).save(any(DocumentEntity.class));
    }

    @Test
    void embeddingFailurePersistsAndListsOnlyTheFixedStage() throws Exception {
        when(embedding.embedAll(anyList())).thenThrow(externalFailure());

        MvcResult result = textRequest()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("文档向量化失败"))
                .andReturn();

        assertSanitizedFailure(result);
        assertThat(saved.get().getStatus()).isEqualTo(DocumentEntity.STATUS_FAILED);
        assertThat(saved.get().getError()).isEqualTo("文档向量化失败");
        mvc.perform(get(BASE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].error").value("文档向量化失败"))
                .andExpect(response -> assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .doesNotContain(BODY_MARKER, CAUSE_MARKER, SUPPRESSED_MARKER));
        assertSanitizedLogs();
    }

    @Test
    void originalPutFailureKeepsDocumentIndexedAndDoesNotLogObjectKeyOrException() throws Exception {
        AtomicReference<String> attemptedKey = new AtomicReference<>();
        when(storage.enabled()).thenReturn(true);
        when(storage.put(anyString(), any(byte[].class), anyString())).thenAnswer(invocation -> {
            attemptedKey.set(invocation.getArgument(0));
            throw externalFailure();
        });
        MockMultipartFile file = new MockMultipartFile("file", "private-source.txt", "text/plain",
                "Synthetic document content for the original-storage boundary.".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart(BASE + "/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INDEXED"))
                .andExpect(jsonPath("$.data.objectKey").doesNotExist());

        assertThat(saved.get().getStatus()).isEqualTo(DocumentEntity.STATUS_INDEXED);
        assertThat(saved.get().getError()).isNull();
        assertThat(attemptedKey.get()).isNotBlank();
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("原件留存失败", "reason=对象存储写入失败"));
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(attemptedKey.get(), "private-source.txt"));
        assertSanitizedLogs();
    }

    @Test
    void failedStatusSaveFailureDoesNotReplaceTheSafeErrorOrLeakToLogs() throws Exception {
        when(embedding.embedAll(anyList())).thenThrow(externalFailure());
        when(documents.save(any(DocumentEntity.class))).thenAnswer(invocation -> {
            DocumentEntity document = invocation.getArgument(0);
            saved.set(document);
            if (DocumentEntity.STATUS_FAILED.equals(document.getStatus())) {
                throw externalFailure();
            }
            return document;
        });

        MvcResult result = textRequest()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("文档向量化失败"))
                .andReturn();

        assertSanitizedFailure(result);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("文档失败状态保存失败"));
    }

    private org.springframework.test.web.servlet.ResultActions textRequest() throws Exception {
        return mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "synthetic-policy", "content",
                        "Synthetic text for the document embedding failure boundary."))));
    }

    private static RuntimeException externalFailure() {
        RuntimeException failure = new IllegalStateException(BODY_MARKER,
                new IllegalStateException(CAUSE_MARKER));
        failure.addSuppressed(new IllegalStateException(SUPPRESSED_MARKER));
        return failure;
    }

    private void assertSanitizedFailure(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(BODY_MARKER, CAUSE_MARKER, SUPPRESSED_MARKER);
        assertThat(result.getResolvedException()).isInstanceOf(BizException.class).satisfies(error -> {
            assertThat(error.getCause()).isNull();
            assertThat(error.getSuppressed()).isEmpty();
            assertThat(error.toString()).doesNotContain(BODY_MARKER, CAUSE_MARKER, SUPPRESSED_MARKER);
        });
        assertSanitizedLogs();
    }

    private void assertSanitizedLogs() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(BODY_MARKER, CAUSE_MARKER, SUPPRESSED_MARKER);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
