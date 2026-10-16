package com.aiflow.engine.memory.longterm;

/**
 * 归并规则：一条候选相对「已有条目」该新建、覆盖、还是丢弃（**纯函数**）。
 *
 * <p>这里写下两条被否掉的做法，免得以后有人「简化」回去（见 docs/long-memory-design.md §3.3）：
 * <ul>
 *   <li>**新的一定赢**：模型读错一次，好数据就被冲掉了；</li>
 *   <li>**置信度更高才赢**：用户改主意后的新说法置信度常常更低（旧的那条本来更「笃定」），于是永远改不过来。</li>
 * </ul>
 * 最终口径是：覆盖与否由模型判「这是纠正还是另一件事」（{@code supersedes}），且遵守人工优先。
 * 代价是抽取提示词里必须带上已有条目的正文，单次调用略贵 —— 这个代价换来的是「改得动」与「冲不掉」。
 */
public final class MemoryMerge {

    public enum Action {
        CREATE,
        UPDATE,
        REJECT
    }

    public record Decision(Action action, String detail) {
    }

    /** 已有条目的必要投影：纯函数里不出现持久化实体。 */
    public record Existing(MemorySource source, String value) {
    }

    private MemoryMerge() {
    }

    public static Decision decide(MemoryCandidate candidate, Existing existing) {
        if (existing == null) {
            return new Decision(Action.CREATE, "new-key");
        }
        if (existing.source() == MemorySource.MANUAL) {
            return new Decision(Action.REJECT, "manual-entry");
        }
        if (existing.value() != null && existing.value().equals(candidate.value())) {
            return new Decision(Action.REJECT, "identical-value");
        }
        if (candidate.supersedes()) {
            return new Decision(Action.UPDATE, "superseded");
        }
        return new Decision(Action.REJECT, "conflict");
    }
}
