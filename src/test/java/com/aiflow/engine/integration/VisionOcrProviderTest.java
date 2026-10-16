package com.aiflow.engine.integration;

import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.ingest.OcrException;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.CapabilityUnavailableException;
import com.aiflow.engine.provider.ChatProvider;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import com.aiflow.engine.provider.ProviderRouter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisionOcrProviderTest {

    private static final String BODY_MARKER = "VENDOR_OCR_BODY_MARKER";
    private static final String CAUSE_MARKER = "VENDOR_OCR_CAUSE_MARKER";
    private static final String SUPPRESSED_MARKER = "VENDOR_OCR_SUPPRESSED_MARKER";

    @Test
    void convertsUpstreamFailureToFixedExceptionWithoutCauseOrSensitiveText() {
        RuntimeException upstream = new RuntimeException(BODY_MARKER,
                new IllegalStateException(CAUSE_MARKER));
        upstream.addSuppressed(new IllegalArgumentException(SUPPRESSED_MARKER));
        VisionOcrProvider provider = new VisionOcrProvider(failingRouter(upstream));

        assertThatThrownBy(() -> provider.recognize(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOfSatisfying(OcrException.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("OCR 调用失败：视觉识别请求未完成");
                    assertThat(error.reason()).isEqualTo(OcrException.Reason.CALL_FAILED);
                    assertThat(error.toString()).doesNotContain(BODY_MARKER, CAUSE_MARKER, SUPPRESSED_MARKER);
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getSuppressed()).isEmpty();
                });
    }

    @Test
    void keepsCapabilityUnavailableMessageStable() {
        VisionOcrProvider provider = new VisionOcrProvider(
                new ProviderRouter(List.of(), ProviderProperties.defaults()));

        assertThatThrownBy(() -> provider.recognize(new byte[]{1}, "image/png"))
                .isInstanceOfSatisfying(OcrException.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("OCR 不可用：没有具备 vision 能力的档位");
                    assertThat(error.reason()).isEqualTo(OcrException.Reason.UNAVAILABLE);
                    assertThat(error.getCause()).isNull();
                });
        assertThat(provider.available()).isFalse();
    }

    @Test
    void keepsTranscriptionAndNoTextSemantics() {
        VisionOcrProvider transcription = new VisionOcrProvider(successRouter("转写后的合成文本"));
        VisionOcrProvider noText = new VisionOcrProvider(successRouter("NO_TEXT"));

        assertThat(transcription.recognize(new byte[]{1}, "image/png")).isEqualTo("转写后的合成文本");
        assertThat(noText.recognize(new byte[]{1}, "image/png")).isEmpty();
    }

    private static ProviderRouter failingRouter(RuntimeException failure) {
        return new ProviderRouter(List.of(), ProviderProperties.defaults()) {
            @Override
            public ChatOutcome chat(Set<String> requiredCapabilities, ChatRequest request,
                                    Consumer<String> sink) {
                throw failure;
            }
        };
    }

    private static ProviderRouter successRouter(String content) {
        return new ProviderRouter(List.of(), ProviderProperties.defaults()) {
            @Override
            public ChatOutcome chat(Set<String> requiredCapabilities, ChatRequest request,
                                    Consumer<String> sink) {
                return new ChatOutcome(new ChatResponse(content, "synthetic-vision", 0, 0),
                        "synthetic-vision", List.of(), List.of());
            }
        };
    }
}
