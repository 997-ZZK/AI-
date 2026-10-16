package com.aiflow.engine.memory.longterm;

import java.util.List;
import java.util.UUID;

/**
 * 长期记忆的**只读**端口：装配上下文时用（实现见 {@code LongMemoryRecallService}）。
 *
 * <p>为什么与写路径分开：一次 {@code llm.chat} 除了调模型不该改任何东西；节点层拿不到「写记忆」的能力，
 * 也就不可能在一次问答里悄悄改用户的档案。
 *
 * <p>实现契约：**不抛异常**。向量服务挂了、表还没迁移、query 异常，一律降级为「没有长期记忆」，
 * 装配结果与没开这个功能时完全一致。这条有专门用例钉住（记忆是增强，不是主链路）。
 */
public interface LongMemoryRecallPort {

    /**
     * 按相关性召回；返回的列表已按分数降序、并做过条数 / 字符预算裁剪。
     *
     * <p>{@code writeBack=false} 时**不记 hits、不动 last_used_at**：预览看一眼不算「用户真的用了这条记忆」，
     * 否则控制台点一次按钮就能把「谁被用过」顶上去。两种口径必须由实现分开对待，所以它是抽象方法 ——
     * 给一个「假装不记账」的默认实现，比让实现方显式写出来更危险。
     */
    List<RecalledMemory> recall(UUID userId, String query, int topK, boolean writeBack);

    /** 装配路径的口径：一次真实问答确实用到了它，命中就记账。 */
    default List<RecalledMemory> recall(UUID userId, String query, int topK) {
        return recall(userId, query, topK, true);
    }

    /** 召回失败累计（观测用）：空实现恒为 0。 */
    default long readFailures() {
        return 0;
    }

    /** 关掉长期记忆时用的空实现（本地零配置、单元测试、节点没有 userId 时）。 */
    static LongMemoryRecallPort disabled() {
        return (userId, query, topK, writeBack) -> List.of();
    }
}
