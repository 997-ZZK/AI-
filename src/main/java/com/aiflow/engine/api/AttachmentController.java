package com.aiflow.engine.api;

import com.aiflow.engine.attachment.AttachmentStore;
import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * 附件上传与读取：图片先进 {@link AttachmentStore}，再以 attachmentId 引用进消息。
 *
 * <p>{@code url} 是应用内相对地址（控制台预览、以及将来「先落地再让厂商来取」的场景）；
 * 云端多模态当前走 data URI 内联上传，不要求这个地址公网可达。
 */
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    private final AttachmentStore attachmentStore;

    public AttachmentController(AttachmentStore attachmentStore) {
        this.attachmentStore = attachmentStore;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AttachmentResponse> upload(@RequestPart("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件读取失败: " + e.getMessage());
        }
        AttachmentStore.Stored stored = attachmentStore.store(file.getOriginalFilename(), bytes);
        return ApiResponse.ok(new AttachmentResponse(stored.id().toString(), stored.mimeType(), stored.sizeBytes(),
                stored.sha256(), "/api/v1/attachments/" + stored.id(), stored.originalName()));
    }

    @GetMapping("/{attachmentId}")
    public ResponseEntity<byte[]> content(@PathVariable UUID attachmentId) {
        AttachmentStore.Loaded loaded = attachmentStore.load(attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(loaded.mimeType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(loaded.bytes());
    }

    public record AttachmentResponse(String attachmentId, String mimeType, long sizeBytes, String sha256,
                                     String url, String originalName) {
    }
}