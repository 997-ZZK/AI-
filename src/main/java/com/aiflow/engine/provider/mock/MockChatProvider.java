package com.aiflow.engine.provider.mock;

import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.node.RunCanceledException;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.ChatImage;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatProvider;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 离线兜底档：确定性回复，用于单测、压测与「什么都没装」时把流程跑通。
 *
 * <p>它也声明 vision 能力，所以图片问答在没有真实多模态档位时仍能走完链路；但回复必须自报家门：
 * 「这里只证明图片进得来、节点跑得通」，绝不假装成真实模型的描述。节点快照里的 provider=mock 是第二条留痕。
 */
@Component
public class MockChatProvider implements ChatProvider {

    private final ProviderProperties properties;

    public MockChatProvider(ProviderProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public Set<String> capabilities() {
        return Set.of(Capabilities.CHAT, Capabilities.VISION);
    }

    @Override
    public String description() {
        return "确定性模拟回复（离线联调与压测用，不代表真实模型质量；视觉请求只回图片摘要）";
    }

    @Override
    public ChatResponse chat(ChatRequest request, Consumer<String> deltaConsumer) {
        ProviderProperties.Mock mock = properties.mock();
        sleep(mock.latencyMs());
        String reply = buildReply(request);
        StringBuilder streamed = new StringBuilder();
        int chunkSize = Math.max(1, mock.chunkSize());
        for (int index = 0; index < reply.length(); index += chunkSize) {
            String chunk = reply.substring(index, Math.min(reply.length(), index + chunkSize));
            streamed.append(chunk);
            if (deltaConsumer != null) {
                deltaConsumer.accept(chunk);
            }
            sleep(mock.chunkDelayMs());
        }
        return new ChatResponse(streamed.toString(), "mock-1", estimateTokens(request.messages()), estimateTokens(reply));
    }

    private String buildReply(ChatRequest request) {
        ChatMessage lastUser = lastUserMessage(request.messages());
        if (lastUser == null) {
            return "（mock）没有收到用户消息。";
        }
        if (lastUser.hasImages()) {
            String media = lastUser.images().stream()
                    .map(ChatImage::mediaType)
                    .distinct()
                    .collect(Collectors.joining("+"));
            return "（mock 视觉）已收到 " + lastUser.images().size() + " 张图片（" + media + "）："
                    + "当前没有可用的真实多模态模型，这里只证明「图片进得来、节点跑得通」。提示词：" + lastUser.content()
                    + "。要真实描述请配置云端 VL 端点（aiflow.provider.endpoints.cloud，capabilities 里声明 vision）。";
        }
        return "（mock）已收到：" + lastUser.content() + "。当前为本地模拟模型回复，用于离线联调与压测。";
    }

    private ChatMessage lastUserMessage(List<ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).role())) {
                return messages.get(index);
            }
        }
        return null;
    }

    private int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 2);
    }

    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimateTokens(message.content());
        }
        return total;
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        }
    }
}