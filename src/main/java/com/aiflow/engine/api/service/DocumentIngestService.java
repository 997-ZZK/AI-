package com.aiflow.engine.api.service;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.ingest.ChunkDraft;
import com.aiflow.engine.ingest.DocumentExtractor;
import com.aiflow.engine.ingest.DocumentParser;
import com.aiflow.engine.ingest.DocumentSourceType;
import com.aiflow.engine.ingest.OcrException;
import com.aiflow.engine.ingest.ParsedDocument;
import com.aiflow.engine.ingest.TextChunker;
import com.aiflow.engine.ingest.UnsupportedDocumentException;
import com.aiflow.engine.persistence.entity.DocumentChunkEntity;
import com.aiflow.engine.persistence.entity.DocumentEntity;
import com.aiflow.engine.persistence.repo.DocumentChunkRepository;
import com.aiflow.engine.persistence.repo.DocumentRepository;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.EmbeddingStore;
import com.aiflow.engine.retrieval.RetrievalException;
import com.aiflow.engine.retrieval.Vectors;
import com.aiflow.engine.storage.ObjectStorage;
import com.aiflow.engine.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文档入库流水线：解析 -> 切片 -> 1024 维向量化 -> 落 documents/document_chunks + 向量列。
 *
 * 刻意不加整体 @Transactional：先落 PENDING，成功后置 INDEXED，失败置 FAILED 并抛出（各自提交），
 * 与 M1「引擎与 DB 的写入不追求跨表强一致」的取舍一致 —— 失败也要留下可排查的记录。
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentParser documentParser;
    private final DocumentExtractor documentExtractor;
    private final TextChunker textChunker;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingStore embeddingStore;
    private final ObjectStorage objectStorage;

    public DocumentIngestService(DocumentRepository documentRepository, DocumentChunkRepository chunkRepository,
                                 DocumentParser documentParser, DocumentExtractor documentExtractor,
                                 TextChunker textChunker,
                                 EmbeddingProvider embeddingProvider, EmbeddingStore embeddingStore,
                                 ObjectStorage objectStorage) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.documentParser = documentParser;
        this.documentExtractor = documentExtractor;
        this.textChunker = textChunker;
        this.embeddingProvider = embeddingProvider;
        this.embeddingStore = embeddingStore;
        this.objectStorage = objectStorage;
    }

    public IngestedDocument ingest(String kbId, String title, DocumentSourceType type, String rawContent,
                                   String sourceUri, UUID createdBy) {
        String safeKbId = requireKbId(kbId);
        String safeTitle = title == null || title.isBlank() ? "未命名文档" : title.strip();
        ParsedDocument parsed;
        try {
            parsed = documentParser.parse(safeTitle, type == null ? DocumentSourceType.TEXT : type, rawContent);
        } catch (UnsupportedDocumentException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        return index(safeKbId, safeTitle, parsed, sourceUri, null, null, createdBy);
    }

    /**
     * 文件入库：PDF 与图片走 {@link DocumentExtractor}（文本层 / OCR），其余按 UTF-8 纯文本处理。
     *
     * <p>与 {@link #ingest} 分开，是为了让「内容从哪来」这件事显式化：一个是调用方给的字符串，
     * 一个是上传的二进制。二进制直接 {@code new String(bytes, UTF_8)} 是错的 —— 乱码会一路切进向量库。
     */
    public IngestedDocument ingestFile(String kbId, String title, String fileName, byte[] bytes, UUID createdBy) {
        String safeKbId = requireKbId(kbId);
        String safeFileName = normalizeFileName(fileName);
        String safeTitle = title == null || title.isBlank() ? safeFileName : title.strip();
        DocumentSourceType type;
        try {
            type = DocumentParser.detectType(safeFileName);
        } catch (UnsupportedDocumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        ParsedDocument parsed;
        if (type == DocumentSourceType.PDF || type == DocumentSourceType.IMAGE) {
            try {
                parsed = documentExtractor.extract(type, safeFileName, bytes);
            } catch (OcrException e) {
                // OCR 不可用属于「能力档位」问题，不是文件格式问题：503 让调用方知道该去配档位
                throw new BizException(e.reason() == OcrException.Reason.UNAVAILABLE
                        || e.reason() == OcrException.Reason.DISABLED
                        ? ErrorCode.CAPABILITY_UNAVAILABLE : ErrorCode.BAD_REQUEST, e.getMessage());
            } catch (UnsupportedDocumentException | IllegalArgumentException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
            }
            parsed = new ParsedDocument(safeTitle, parsed.type(), parsed.content(), parsed.contentHash(), parsed.pages());
        } else {
            String content = new String(bytes == null ? new byte[0] : bytes, StandardCharsets.UTF_8);
            try {
                parsed = documentParser.parse(safeTitle, type, content);
            } catch (UnsupportedDocumentException | IllegalArgumentException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
            }
        }
        return index(safeKbId, safeTitle, parsed, safeFileName, safeFileName, bytes, createdBy);
    }

    private IngestedDocument index(String safeKbId, String safeTitle, ParsedDocument parsed, String sourceUri,
                                   String originalFileName, byte[] originalBytes, UUID createdBy) {
        Optional<DocumentEntity> duplicate;
        try {
            duplicate = documentRepository.findByKbIdAndContentHash(safeKbId, parsed.contentHash());
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文档索引写入失败");
        }
        if (duplicate.isPresent()) {
            DocumentEntity existing = duplicate.get();
            log.info("document duplicated hash={} document={}", parsed.contentHash(), existing.getId());
            return new IngestedDocument(existing.getId(), existing.getTitle(), existing.getStatus(),
                    existing.getChunkCount(), true, existing.getObjectKey(), existing.getObjectBytes());
        }
        DocumentEntity document;
        try {
            document = documentRepository.save(new DocumentEntity(UUID.randomUUID(), safeKbId, safeTitle,
                    parsed.type(), sourceUri, parsed.contentHash(), createdBy, Instant.now()));
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文档索引写入失败");
        }
        // 原件留存放在切片之前：切片失败时这份文档是 FAILED，而"失败时的原件"恰恰是最该留下的排障材料
        storeOriginal(document, originalFileName, originalBytes);
        String failureMessage = "文档切片失败";
        try {
            List<ChunkDraft> drafts = parsed.pages().isEmpty()
                    ? textChunker.chunk(parsed.content())
                    : textChunker.chunkPages(parsed.pages());
            if (drafts.isEmpty()) {
                throw new IllegalStateException("切片结果为空，请检查文档内容");
            }
            failureMessage = "文档向量化失败";
            List<float[]> vectors = embeddingProvider.embedAll(drafts.stream().map(ChunkDraft::content).toList());
            if (vectors.size() != drafts.size()) {
                throw new IllegalStateException("embedding 返回条数与切片数不一致: "
                        + vectors.size() + " != " + drafts.size());
            }
            Map<String, float[]> embeddings = new LinkedHashMap<>();
            List<DocumentChunkEntity> chunks = new ArrayList<>(drafts.size());
            for (int index = 0; index < drafts.size(); index++) {
                float[] vector = vectors.get(index);
                Vectors.requireDimensions(vector, embeddingProvider.dimensions(),
                        "embedding provider " + embeddingProvider.id());
                UUID chunkId = UUID.randomUUID();
                embeddings.put(chunkId.toString(), vector);
                chunks.add(new DocumentChunkEntity(chunkId, document, safeKbId, drafts.get(index), Instant.now()));
            }
            failureMessage = "文档索引写入失败";
            chunkRepository.saveAll(chunks);
            embeddingStore.upsertAll(safeKbId, embeddingProvider.id(), embeddings);
            document.markIndexed(chunks.size(), Instant.now());
            documentRepository.save(document);
            log.info("ingested document {} chunks={}", document.getId(), chunks.size());
            return new IngestedDocument(document.getId(), document.getTitle(), document.getStatus(), chunks.size(),
                    false, document.getObjectKey(), document.getObjectBytes());
        } catch (RuntimeException e) {
            document.markFailed(failureMessage, Instant.now());
            try {
                documentRepository.save(document);
            } catch (RuntimeException persistenceFailure) {
                log.warn("文档失败状态保存失败 document={}", document.getId());
            }
            throw new BizException(e instanceof RetrievalException
                    ? ErrorCode.CAPABILITY_UNAVAILABLE : ErrorCode.INTERNAL_ERROR, failureMessage);
        }
    }

    /**
     * 把上传的原件放进对象存储，并在文档行上记下位置。
     *
     * <p>**失败只 WARN，不影响入库**：留存是旁路能力 —— 对象存储挂了就让用户连知识库都用不了，
     * 那是把可选依赖做成了硬依赖。调用方从 objectKey 是否为空就能看出留没留住（响应里直接给这个字段）。
     */
    private void storeOriginal(DocumentEntity document, String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        String key = objectKey(document.getKbId(), document.getId(), fileName);
        try {
            if (!objectStorage.enabled()) {
                return;
            }
            StoredObject stored = objectStorage.put(key, bytes, contentTypeOf(fileName));
            document.markObjectStored(stored.key(), stored.size(), Instant.now());
            documentRepository.save(document);
            log.info("原件已留存 document={} bytes={}", document.getId(), stored.size());
        } catch (RuntimeException e) {
            log.warn("原件留存失败（入库不受影响，object_key 留空）: document={} reason=对象存储写入失败",
                    document.getId());
        }
    }

    /** 对象键：按 kb / document 分层，便于按前缀批量清理；文件名做一次净化，防止把路径分隔符写进键里。 */
    static String objectKey(String kbId, UUID documentId, String fileName) {
        String safeName = normalizeFileName(fileName == null || fileName.isBlank() ? "original" : fileName);
        return "kb/" + kbId + "/documents/" + documentId + "/" + safeName;
    }

    private static String normalizeFileName(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "upload.txt" : fileName.strip();
        name = name.replaceAll("[\\\\/\\p{Cc}\\p{Zl}\\p{Zp}]+", "_");
        if (name.length() <= 200) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String suffix = dot >= 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
        return name.substring(0, 200 - suffix.length()) + suffix;
    }

    /** 只按扩展名给一个够用的 MIME：原件下载要靠它，猜错会让浏览器直接下载而不是预览。 */
    static String contentTypeOf(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".yml")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    private String requireKbId(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "kbId 不能为空");
        }
        String trimmed = kbId.strip();
        if (trimmed.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "kbId 长度不能超过 64");
        }
        if (trimmed.chars().anyMatch(value -> Character.isISOControl(value) || value == '/' || value == '\\')) {
            throw new BizException(ErrorCode.BAD_REQUEST, "kbId 不能包含控制字符或路径分隔符");
        }
        return trimmed;
    }

    /** objectKey / objectBytes 为 null = 原件没留住（能力未开启或留存失败）。 */
    public record IngestedDocument(UUID documentId, String title, String status, int chunkCount, boolean duplicate,
                                   String objectKey, Long objectBytes) {
    }
}
