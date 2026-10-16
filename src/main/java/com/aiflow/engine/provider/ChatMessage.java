package com.aiflow.engine.provider;

import java.util.List;

/**
 * 一次对话中的一条消息。
 *
 * <p>{@code images} 只在多模态请求里非空：有图时 provider 把 content 序列化成 OpenAI 的多模态数组
 * （text + image_url 两种 part），无图时保持纯字符串 —— 大量兼容网关对数组形态支持不全，无谓地改会踩到它们。
 */
public record ChatMessage(String role, String content, List<ChatImage> images) {

    public ChatMessage {
        role = role == null || role.isBlank() ? "user" : role;
        content = content == null ? "" : content;
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** 纯文本消息（绝大多数调用）。 */
    public ChatMessage(String role, String content) {
        this(role, content, List.of());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of());
    }

    public static ChatMessage user(String content, List<ChatImage> images) {
        return new ChatMessage("user", content, images);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, List.of());
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }
}