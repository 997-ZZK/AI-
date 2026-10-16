package com.aiflow.engine.provider;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatImageTest {

    private static final byte[] PNG = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};

    @Test
    void acceptsHttpAndHttpsLinks() {
        assertThat(ChatImage.ofUrl("http://localhost:9000/a.png").inline()).isFalse();
        assertThat(ChatImage.ofUrl(" https://cdn.example.com/a.jpg ").url())
                .isEqualTo("https://cdn.example.com/a.jpg");
    }

    @Test
    void rejectsReferencesThatAreNotImages() {
        assertThatThrownBy(() -> ChatImage.ofUrl("")).hasMessageContaining("图片引用为空");
        assertThatThrownBy(() -> ChatImage.ofUrl("file:///etc/passwd"))
                .hasMessageContaining("不支持的图片引用");
        assertThatThrownBy(() -> ChatImage.ofUrl("data:text/plain;base64,QUJD"))
                .as("data URI 必须是 image/*：否则等于允许把任意内容当图片发给厂商")
                .hasMessageContaining("不支持的图片引用");
    }

    @Test
    void encodesBytesAsInlineDataUri() {
        ChatImage image = ChatImage.ofBytes("image/png", PNG);

        assertThat(image.inline()).isTrue();
        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(image.url()).isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG));
    }

    @Test
    void rejectsEmptyPayloadAndNonImageMime() {
        assertThatThrownBy(() -> ChatImage.ofBytes("image/png", new byte[0])).hasMessageContaining("图片内容为空");
        assertThatThrownBy(() -> ChatImage.ofBytes("text/plain", PNG)).hasMessageContaining("不是图片 MIME");
    }

    @Test
    void summaryNeverLeaksBase64Payload() {
        ChatImage image = ChatImage.ofBytes("image/jpeg", PNG);

        assertThat(image.toString()).contains("image/jpeg").contains("inline");
        assertThat(image.toString()).doesNotContain(Base64.getEncoder().encodeToString(PNG));
    }
}