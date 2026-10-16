package com.aiflow.engine.tool;

import com.aiflow.engine.config.ToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行级护栏：调用次数上限、工具循环指纹、幂等重放缓存。
 *
 * <p>三个问题，一个组件解决，因为它们的生命周期完全一致 —— 都是「同一次运行内」：
 * <ul>
 *   <li>次数上限：一次运行里工具被调用太多次，通常是工作流写错（条件边绕回），不该等到烧完配额才发现</li>
 *   <li>循环指纹：同一工具 + 同一入参反复出现（模型或条件边在原地打转），超过阈值直接中止并给用户预设话术</li>
 *   <li>幂等：写操作（POST/PUT/PATCH/DELETE）失败重试时不能重复产生副作用 —— 同指纹直接回放上次结果</li>
 * </ul>
 *
 * <p>状态放在进程内存里、按最近使用淘汰：单实例足够，多实例下同一 run 只会落在一个节点上执行（会话串行），
 * 所以内存态是对的；等事件总线换 Redis Stream（M4）时一并挪走。
 */
@Component
public class ToolInvocationGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolInvocationGuard.class);
    private static final int MAX_TRACKED_RUNS = 500;

    private final ToolProperties properties;
    private final Map<String, RunState> runs = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RunState> eldest) {
            if (size() > MAX_TRACKED_RUNS) {
                log.debug("tool guard 淘汰运行 {}", eldest.getKey());
                return true;
            }
            return false;
        }
    };

    public ToolInvocationGuard(ToolProperties properties) {
        this.properties = properties;
    }

    /** 调用前检查：先看总次数，再看同指纹重复。返回本运行内的第几次调用（从 1 开始）。 */
    public synchronized int beforeCall(String runId, String toolId, String fingerprint) {
        RunState state = runs.computeIfAbsent(runId, key -> new RunState());
        state.calls++;
        if (state.calls > properties.maxCallsPerRun()) {
            throw new ToolBlockedException("本次运行的工具调用次数已达上限 "
                    + properties.maxCallsPerRun() + " 次（aiflow.tools.max-calls-per-run）");
        }
        int repeats = state.fingerprints.merge(fingerprint, 1, Integer::sum);
        if (repeats > properties.loopThreshold()) {
            throw new ToolBlockedException("工具循环检测：工具 " + toolId + " 以相同入参重复调用 " + repeats
                    + " 次（阈值 " + properties.loopThreshold() + "），已中止本次运行");
        }
        return state.calls;
    }

    /** 幂等命中：同一次运行里同指纹的写操作已经成功过，直接回放，不再发第二次请求。 */
    public synchronized Cached lookup(String runId, String fingerprint) {
        RunState state = runs.get(runId);
        return state == null ? null : state.idempotent.get(fingerprint);
    }

    public synchronized void remember(String runId, String fingerprint, Cached result) {
        runs.computeIfAbsent(runId, key -> new RunState()).idempotent.put(fingerprint, result);
    }

    /** 运行结束清理。未清理也不会泄漏：按最近使用淘汰到 MAX_TRACKED_RUNS 条。 */
    public synchronized void clear(String runId) {
        runs.remove(runId);
    }

    public synchronized int callsOf(String runId) {
        RunState state = runs.get(runId);
        return state == null ? 0 : state.calls;
    }

    public record Cached(int httpStatus, String body, boolean truncated) {
    }

    private static final class RunState {
        private int calls;
        private final Map<String, Integer> fingerprints = new LinkedHashMap<>();
        private final Map<String, Cached> idempotent = new LinkedHashMap<>();
    }
}