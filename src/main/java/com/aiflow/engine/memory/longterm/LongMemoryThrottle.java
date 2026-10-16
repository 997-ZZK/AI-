package com.aiflow.engine.memory.longterm;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 抽取节流（**进程内记账**）：三条阈值同时满足才允许再抽一次。
 *
 * <p>为什么要有它：抽取是「一次对话调用 + 逐条 embedding」，是这条链上最贵的一步。没有节流的话，
 * 用户每说一句都会触发一次；加了之后是「攒够一批再抽」。这与 M4-1 摘要的 chunk-size 是同一个思路，
 * 只是判据换成了「会话消息数」而不是「可折叠区间」。
 *
 * <p>为什么不落库：这是**成本**控制，不是正确性约束。进程重启后记账清零，最坏后果是某会话多抽一次；
 * 而落库要么动 M4-1 的表（额外 schema 变更），要么新开一张只为省一次调用的表 —— 都不值。
 *
 * <p>失败也推进记账（start 时就推进）：否则一个每轮都失败的会话会每轮烧一次调用。代价是失败后要再攒一批，
 * 这是刻意的取舍 —— 少抽一次没有正确性代价，多抽一次是钱。
 */
@Component
public class LongMemoryThrottle {

    /** 记账表的 LRU 上限：会话数再多也不会把内存堆起来。被淘汰的会话下次按「从未抽取」处理。 */
    static final int MAX_TRACKED_SESSIONS = 10_000;

    private final Map<UUID, State> states = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, State> eldest) {
            return size() > MAX_TRACKED_SESSIONS;
        }
    };

    /** 返回是否应该现在抽取；返回 true 时已把该会话标记为在途（用完必须 {@link #finish}）。 */
    public synchronized Decision start(UUID sessionId, long totalMessages, Instant now,
                                       LongMemoryProperties properties) {
        if (sessionId == null) {
            return new Decision(false, "no-session");
        }
        State state = states.get(sessionId);
        if (state == null) {
            state = new State();
            states.put(sessionId, state);
        }
        if (state.inFlight) {
            return new Decision(false, "in-flight");
        }
        if (totalMessages < properties.extractMinMessages()) {
            return new Decision(false, "not-enough-messages");
        }
        if (state.lastExtractMessageCount >= 0) {
            long fresh = totalMessages - state.lastExtractMessageCount;
            if (fresh < properties.extractEveryMessages()) {
                return new Decision(false, "not-enough-new-messages");
            }
            if (state.lastExtractAt != null) {
                Duration since = Duration.between(state.lastExtractAt, now);
                if (since.compareTo(properties.extractMinInterval()) < 0) {
                    return new Decision(false, "too-soon");
                }
            }
        }
        state.inFlight = true;
        state.lastExtractMessageCount = totalMessages;
        state.lastExtractAt = now;
        return new Decision(true, "ok");
    }

    /** 抽取结束（无论成败）后调用：清掉在途标记。失败**不**回退记账，理由见类注释。 */
    public synchronized void finish(UUID sessionId) {
        State state = states.get(sessionId);
        if (state != null) {
            state.inFlight = false;
        }
    }

    public synchronized int trackedSessions() {
        return states.size();
    }

    public record Decision(boolean extract, String reason) {
    }

    private static final class State {
        private boolean inFlight;
        private long lastExtractMessageCount = -1;
        private Instant lastExtractAt;
    }
}
