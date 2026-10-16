package com.aiflow.engine.attachment;

import com.aiflow.engine.common.Hashes;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.config.AttachmentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 附件（图片）存储：内容落盘，元数据写同名 sidecar json。
 *
 * <p>为什么先不建表：附件当前只服务「一次运行里的图片输入」，建表就要连带处理生命周期（删除、归档、级联删向量），
 * 而暂无消费者。sidecar + 目录约定足够，等对象存储（M2 待补的 MinIO）落地时整体替换。
 *
 * <p>安全约定（每条都对应一个真实攻击面）：
 * <ul>
 *   <li>类型以<b>文件头嗅探</b>为准，改扩展名无效；只收 png / jpeg / webp / gif</li>
 *   <li>落盘文件名由服务端生成（UUID + 嗅探出的扩展名），原始文件名只作为元数据保存且做清洗截断，不参与路径拼接</li>
 *   <li>读取时由 id 反查元数据，路径必须落在配置目录内（防目录穿越）</li>
 *   <li>日志只记 id / 类型 / 大小 / 摘要，不记内容</li>
 * </ul>
 */
@Service
public class AttachmentStore {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStore.class);
    private static final int ORIGINAL_NAME_LIMIT = 120;

    private final AttachmentProperties properties;
    private final ObjectMapper objectMapper;
    private final Path root;

    public AttachmentStore(AttachmentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.root = Path.of(properties.dir()).toAbsolutePath().normalize();
    }

    /** 落盘并返回元数据。类型以文件头为准，超限与未知类型直接拒绝。 */
    public Stored store(String originalName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上传内容为空");
        }
        if (bytes.length > properties.maxBytes()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "图片超过上限：" + bytes.length + " > " + properties.maxBytes() + " 字节");
        }
        ImageKind kind = ImageKind.sniff(bytes).orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                "只接受 png / jpeg / webp / gif 图片（按文件头识别，改扩展名无效）"));
        UUID id = UUID.randomUUID();
        String sha256 = Hashes.sha256Hex(bytes);
        Instant createdAt = Instant.now();
        String name = sanitizeName(originalName);
        try {
            Files.createDirectories(root);
            Files.write(contentPath(id, kind), bytes);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("id", id.toString());
            metadata.put("mimeType", kind.mimeType());
            metadata.put("sizeBytes", bytes.length);
            metadata.put("sha256", sha256);
            metadata.put("originalName", name);
            metadata.put("createdAt", createdAt.toString());
            Files.write(metadataPath(id), objectMapper.writeValueAsBytes(metadata));
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "附件写入失败: " + e.getMessage());
        }
        log.info("attachment stored id={} mime={} bytes={} sha256={}", id, kind.mimeType(), bytes.length,
                sha256.substring(0, 12));
        return new Stored(id, kind.mimeType(), bytes.length, sha256, name, createdAt);
    }

    /** 只读元数据，不加载内容：上传后校验存在性用这个。 */
    public Optional<Stored> find(UUID id) {
        Path metadataPath = resolveInsideRoot(metadataPath(id));
        if (!Files.isRegularFile(metadataPath)) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readAllBytes(metadataPath));
            return Optional.of(new Stored(id, node.path("mimeType").asText("image/png"),
                    node.path("sizeBytes").asLong(0), node.path("sha256").asText(""),
                    node.path("originalName").asText(""), Instant.parse(node.path("createdAt").asText(Instant.EPOCH.toString()))));
        } catch (IOException | RuntimeException e) {
            log.error("attachment metadata unreadable id={} path={}", id, metadataPath, e);
            return Optional.empty();
        }
    }

    public Stored require(UUID id) {
        return find(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "附件不存在或已被清理: " + id));
    }

    /** 读回内容，供多模态节点编码成 data URI。 */
    public Loaded load(UUID id) {
        Stored metadata = require(id);
        ImageKind kind = ImageKind.byMime(metadata.mimeType())
                .orElseThrow(() -> new BizException(ErrorCode.INTERNAL_ERROR, "附件类型不可用: " + metadata.mimeType()));
        Path contentPath = resolveInsideRoot(contentPath(id, kind));
        if (!Files.isRegularFile(contentPath)) {
            throw new BizException(ErrorCode.NOT_FOUND, "附件内容缺失（元数据在、文件不在）: " + id);
        }
        try {
            return new Loaded(id, metadata.mimeType(), Files.readAllBytes(contentPath), metadata.sha256());
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "附件读取失败: " + e.getMessage());
        }
    }

    private Path contentPath(UUID id, ImageKind kind) {
        return root.resolve(id + "." + kind.extension());
    }

    private Path metadataPath(UUID id) {
        return root.resolve(id + ".json");
    }

    private Path resolveInsideRoot(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "附件路径越界，已拒绝: " + normalized.getFileName());
        }
        return normalized;
    }

    private static String sanitizeName(String originalName) {
        String name = originalName == null ? "" : originalName.strip();
        if (name.isEmpty()) {
            return "unnamed";
        }
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("\\p{Cntrl}", "");
        return name.length() <= ORIGINAL_NAME_LIMIT ? name : name.substring(0, ORIGINAL_NAME_LIMIT);
    }

    public record Stored(UUID id, String mimeType, long sizeBytes, String sha256, String originalName,
                         Instant createdAt) {
    }

    public record Loaded(UUID id, String mimeType, byte[] bytes, String sha256) {
    }

}