package com.aiflow.engine.attachment;

import com.aiflow.engine.common.Hashes;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.config.AttachmentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentStoreTest {

    /** 真实的 1x1 PNG：类型嗅探看文件头，用假字节会掩盖「头识别对不对」这件事。 */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path tempDir;

    @Test
    void storesAndLoadsContentWithHash() {
        AttachmentStore store = store(1024 * 1024, 4);

        AttachmentStore.Stored stored = store.store("cat.png", PNG);
        AttachmentStore.Loaded loaded = store.load(stored.id());

        assertThat(stored.mimeType()).isEqualTo("image/png");
        assertThat(stored.sizeBytes()).isEqualTo(PNG.length);
        assertThat(stored.sha256()).isEqualTo(Hashes.sha256Hex(PNG)).hasSize(64);
        assertThat(loaded.bytes()).isEqualTo(PNG);
        assertThat(store.require(stored.id()).originalName()).isEqualTo("cat.png");
    }

    @Test
    void sniffsTypeFromContentNotFromFileName() {
        AttachmentStore store = store(1024 * 1024, 4);

        assertThat(store.store("pretend.txt", PNG).mimeType())
                .as("改扩展名不该改变服务端认定的类型")
                .isEqualTo("image/png");
        assertThatThrownBy(() -> store.store("evil.png", "这不是图片".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("按文件头识别");
    }

    @Test
    void rejectsOversizedUpload() {
        AttachmentStore store = store(16, 4);

        assertThatThrownBy(() -> store.store("big.png", PNG))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过上限");
    }

    @Test
    void stripsPathFromOriginalName() {
        AttachmentStore store = store(1024 * 1024, 4);

        assertThat(store.store("..\\..\\evil.png", PNG).originalName()).isEqualTo("evil.png");
        assertThat(store.store("a/b/c.png", PNG).originalName()).isEqualTo("c.png");
    }

    @Test
    void unknownIdIsNotFound() {
        AttachmentStore store = store(1024 * 1024, 4);

        assertThatThrownBy(() -> store.require(UUID.randomUUID()))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(store.find(UUID.randomUUID())).isEmpty();
    }

    private AttachmentStore store(long maxBytes, int maxPerRequest) {
        return new AttachmentStore(new AttachmentProperties(tempDir.toString(), maxBytes, maxPerRequest),
                new ObjectMapper());
    }
}