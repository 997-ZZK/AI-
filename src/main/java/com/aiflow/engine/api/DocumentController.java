package com.aiflow.engine.api;

import com.aiflow.engine.api.service.DocumentIngestService;
import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.ingest.DocumentSourceType;
import com.aiflow.engine.persistence.entity.DocumentChunkEntity;
import com.aiflow.engine.persistence.entity.DocumentEntity;
import com.aiflow.engine.persistence.repo.DocumentChunkRepository;
import com.aiflow.engine.persistence.repo.DocumentRepository;
import com.aiflow.engine.storage.ObjectStorage;
import com.aiflow.engine.storage.StorageException;
import com.aiflow.engine.storage.StoredObject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kbs/{kbId}/documents")
public class DocumentController {

    private final DocumentIngestService ingestService;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ObjectStorage objectStorage;

    public DocumentController(DocumentIngestService ingestService, DocumentRepository documentRepository,
                              DocumentChunkRepository chunkRepository, ObjectStorage objectStorage) {
        this.ingestService = ingestService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.objectStorage = objectStorage;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<DocumentResponse> ingestText(@PathVariable String kbId,
                                                    @Valid @RequestBody IngestTextRequest request) {
        return ApiResponse.ok(toResponse(ingestService.ingest(kbId, request.title(),
                request.sourceType() == null ? DocumentSourceType.TEXT : request.sourceType(),
                request.content(), request.sourceUri(), request.createdBy())));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentResponse> upload(@PathVariable String kbId,
                                                @RequestPart("file") MultipartFile file,
                                                @RequestParam(value = "title", required = false) String title,
                                                @RequestParam(value = "createdBy", required = false) UUID createdBy) {
        String fileName = file.getOriginalFilename() == null ? "upload.txt" : file.getOriginalFilename();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件读取失败");
        }
        // 交给 ingestFile：PDF / 图片在这里分诊（文本层或 OCR），不是先按 UTF-8 解码再解析
        return ApiResponse.ok(toResponse(ingestService.ingestFile(kbId, title, fileName, bytes, createdBy)));
    }

    @GetMapping
    public ApiResponse<List<DocumentSummary>> list(@PathVariable String kbId) {
        return ApiResponse.ok(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(DocumentController::toSummary)
                .toList());
    }

    @GetMapping("/{documentId}/chunks")
    public ApiResponse<List<ChunkSummary>> chunks(@PathVariable String kbId, @PathVariable UUID documentId) {
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文档不存在: " + documentId));
        if (!document.getKbId().equals(kbId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文档不属于该知识库: " + documentId);
        }
        return ApiResponse.ok(chunkRepository.findByDocumentIdOrderByChunkIndex(documentId).stream()
                .map(DocumentController::toChunkSummary)
                .toList());
    }

    /**
     * 下载原件（PDF / 图片的上传副本）。
     *
     * <p>三种"没有原件"要分开报，它们的排障结论完全不同：**能力没开** -> 503（去开配置）、
     * **这份文档没留存**（能力开着但这次留存失败过）-> 404 且说明原因、**对象存储里对象不见了** -> 404
     * 且说明是对象存储侧的问题（桶被清过？保留策略？）。
     *
     * <p>流式返回而不是一次性读进内存：原件上限是 20MB（multipart 限制），但没理由为它占 20MB 堆。
     */
    @GetMapping("/{documentId}/original")
    public ResponseEntity<InputStreamResource> original(@PathVariable String kbId, @PathVariable UUID documentId) {
        DocumentEntity document = requireDocument(kbId, documentId);
        // 配置可能在文档写入后被关闭；已有 object_key 也不能绕过能力开关去访问旧对象。
        boolean storageEnabled;
        try {
            storageEnabled = objectStorage.enabled();
        } catch (RuntimeException ignored) {
            throw new BizException(ErrorCode.CAPABILITY_UNAVAILABLE, "对象存储不可用");
        }
        if (!storageEnabled) {
            throw new BizException(ErrorCode.CAPABILITY_UNAVAILABLE, "原件留存未开启，无法下载原件");
        }
        if (document.getObjectKey() == null || document.getObjectKey().isBlank()) {
            throw new BizException(ErrorCode.NOT_FOUND, "这份文档没有留存原件（入库时留存失败）");
        }
        Optional<StoredObject> metadata;
        try {
            metadata = objectStorage.stat(document.getObjectKey());
        } catch (RuntimeException ignored) {
            // 对象存储不可用属于能力问题（503），不是"这份文档没有"（404）
            throw new BizException(ErrorCode.CAPABILITY_UNAVAILABLE, "对象存储元信息查询失败");
        }
        if (metadata == null) {
            throw new BizException(ErrorCode.CAPABILITY_UNAVAILABLE, "对象存储元信息查询失败");
        }
        StoredObject stat = metadata.orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                "原件已不在对象存储里"));
        if (stat.size() < 0) {
            throw new BizException(ErrorCode.CAPABILITY_UNAVAILABLE, "对象存储元信息查询失败");
        }
        // 元数据来自可插拔存储。所有可能解析/拒绝头部的操作都在 open 前完成，
        // 既不让非法 MIME 进入 converter 的异常日志，也不遗留已打开却未交付的流。
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(downloadContentType(stat.contentType()));
        responseHeaders.setContentLength(stat.size());
        responseHeaders.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(document.getObjectKey()));
        responseHeaders.set("X-Object-Key", headerValue(document.getObjectKey()));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().headers(responseHeaders);
        InputStream stream;
        try {
            // stat 成功后对象仍可能被清理；open 的失败必须沿用同一套 404/503 分类。
            stream = objectStorage.open(document.getObjectKey());
        } catch (StorageException e) {
            if (e.isNotFound()) {
                throw new BizException(ErrorCode.NOT_FOUND, "原件已不在对象存储里");
            }
            throw new BizException(ErrorCode.CAPABILITY_UNAVAILABLE, "对象存储读取失败");
        } catch (RuntimeException ignored) {
            throw new BizException(ErrorCode.CAPABILITY_UNAVAILABLE, "对象存储读取失败");
        }
        if (stream == null) {
            throw new BizException(ErrorCode.CAPABILITY_UNAVAILABLE, "对象存储读取失败");
        }
        return response.body(new InputStreamResource(new SafeOriginalStream(stream)));
    }

    private DocumentEntity requireDocument(String kbId, UUID documentId) {
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文档不存在: " + documentId));
        if (!document.getKbId().equals(kbId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文档不属于该知识库: " + documentId);
        }
        return document;
    }

    /** 文件名只取对象键的最后一段，并用 RFC 5987 的形式给出：中文文件名走 filename* 才不乱码。 */
    private static String contentDisposition(String objectKey) {
        String fileName = headerValue(objectKey.substring(objectKey.lastIndexOf('/') + 1))
                .replace('\\', '_');
        String encoded = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "inline; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;
    }

    private static String headerValue(String value) {
        return value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", "_");
    }

    private static MediaType downloadContentType(String contentType) {
        if (contentType == null || contentType.isBlank()
                || contentType.codePoints().anyMatch(value -> Character.isISOControl(value)
                || Character.getType(value) == Character.LINE_SEPARATOR
                || Character.getType(value) == Character.PARAGRAPH_SEPARATOR)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            if (!parsed.isConcrete()) {
                return MediaType.APPLICATION_OCTET_STREAM;
            }
            // 原件只需要类型/子类型；不透传 charset、boundary 或厂商自定义参数。
            return new MediaType(parsed.getType(), parsed.getSubtype());
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** 网络流读取/关闭发生在 Controller 返回后；不要把对象存储的延迟异常交给普通日志。 */
    private static final class SafeOriginalStream extends FilterInputStream {

        private SafeOriginalStream(InputStream delegate) {
            super(delegate);
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (IOException | RuntimeException ignored) {
                throw readFailure();
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                return in.read(bytes, offset, length);
            } catch (IOException | RuntimeException ignored) {
                throw readFailure();
            }
        }

        @Override
        public long skip(long bytes) throws IOException {
            try {
                return in.skip(bytes);
            } catch (IOException | RuntimeException ignored) {
                throw readFailure();
            }
        }

        @Override
        public int available() throws IOException {
            try {
                return in.available();
            } catch (IOException | RuntimeException ignored) {
                throw readFailure();
            }
        }

        @Override
        public synchronized void reset() throws IOException {
            try {
                in.reset();
            } catch (IOException | RuntimeException ignored) {
                throw readFailure();
            }
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } catch (IOException | RuntimeException ignored) {
                throw readFailure();
            }
        }

        private static IOException readFailure() {
            return new IOException("对象存储读取失败");
        }
    }

    private DocumentResponse toResponse(DocumentIngestService.IngestedDocument document) {
        return new DocumentResponse(document.documentId(), document.title(), document.status(), document.chunkCount(),
                document.duplicate(), document.objectKey(), document.objectBytes());
    }

    private static DocumentSummary toSummary(DocumentEntity document) {
        return new DocumentSummary(document.getId(), document.getTitle(), document.getSourceType().name(),
                document.getStatus(), document.getChunkCount(), document.getError(), document.getObjectKey(),
                document.getObjectBytes(), document.getCreatedAt());
    }

    private static ChunkSummary toChunkSummary(DocumentChunkEntity chunk) {
        return new ChunkSummary(chunk.getId().toString(), chunk.getChunkIndex(), chunk.getSectionPath(),
                chunk.getPageFrom(), chunk.getPageTo(), chunk.getCharCount(), chunk.getEmbeddingModel(),
                chunk.getContent().length() > 200 ? chunk.getContent().substring(0, 200) : chunk.getContent());
    }

    public record IngestTextRequest(
            @NotBlank String title,
            String content,
            DocumentSourceType sourceType,
            String sourceUri,
            UUID createdBy
    ) {
    }

    /** objectKey / objectBytes 为 null = 原件未留存（能力未开启或留存失败）——响应里直接可见，不用翻日志。 */
    public record DocumentResponse(UUID documentId, String title, String status, int chunkCount, boolean duplicate,
                                   String objectKey, Long objectBytes) {
    }

    public record DocumentSummary(UUID documentId, String title, String sourceType, String status, int chunkCount,
                                  String error, String objectKey, Long objectBytes, Instant createdAt) {
    }

    public record ChunkSummary(String chunkId, int chunkIndex, String sectionPath, Integer pageFrom, Integer pageTo,
                               int charCount, String embeddingModel, String preview) {
    }
}
