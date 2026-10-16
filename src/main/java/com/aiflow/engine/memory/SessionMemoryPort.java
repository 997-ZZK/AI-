package com.aiflow.engine.memory;

import com.aiflow.engine.provider.ChatMessage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话记忆的**只读**端口：节点装配上下文时用（JPA 实现见 {@code persistence/JpaSessionMemoryAdapter}）。
 *
 * <p>只有读：写路径（滚动摘要）是旁路维护，由 api/service 编排 —— 一次 {@code llm.chat} 除了调模型
 * 不该改任何东西，节点层也就不该拿到「写记忆」的能力。
 *
 * <p>实现契约：**不抛异常**。读库失败、表不存在、内容异常，一律降级为「没有记忆」并在实现里计数。
 * 记忆是增强不是主链路：它坏了，运行也必须照跑（这条有专门用例钉住）。
 */
public interface SessionMemoryPort {

    /** 最近 {@code limit} 条消息，**从旧到新**；不含 system、不含空内容。 */
    List<ChatMessage> history(UUID sessionId, int limit);

    /** 当前滚动摘要；没有就是 empty。 */
    Optional<String> summary(UUID sessionId);

    /** 读失败累计（观测用，见 GET /api/v1/sessions/{id}/memory）：空实现恒为 0。 */
    default long readFailures() {
        return 0;
    }

    /** 记忆关掉时用的空实现（本地零配置、单元测试）。 */
    static SessionMemoryPort disabled() {
        return new SessionMemoryPort() {
            @Override
            public List<ChatMessage> history(UUID sessionId, int limit) {
                return List.of();
            }

            @Override
            public Optional<String> summary(UUID sessionId) {
                return Optional.empty();
            }
        };
    }
}
