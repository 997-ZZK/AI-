package com.aiflow.engine.safety;

import com.aiflow.engine.config.SafetyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 流式增量内容审核：模型一边生成、规则一边跑，命中的内容**在被推送之前**拦下。
 *
 * <p>为什么必须做这一层：输出审核原本只作用于完整回复，而 SSE 片段在生成过程中就已经推送给用户了 ——
 * 「最终答案是预设话术」与「用户已经读到了违规片段」可以同时成立。审核要真的有效，就得跟着流走。
 *
 * <p>做法是**保留窗口**：最近「生效窗口」个字符先留着不推送，等下一批片段到达时连同窗口一起检查，
 * 通过后再放行窗口之前的部分。窗口内不会出现「已推送出去才发现命中」。
 *
 * <p>不漏的原理（窗口重叠）：每次检查的是「上一段的末尾 (窗口-1) 个字符 + 本段候选」，
 * 因此任何长度不超过**生效窗口**的命中串，只要它有一个字符落在本段里，就一定完整地出现在本次检查窗口内；
 * 而一旦命中，候选段（含命中串）不会被发出。所以**承诺是：长度 ≤ 生效窗口（{@link #window()}）的命中，
 * 整词绝不会出现在已推送内容里**；但放行是按冲刷边界走的、不按词边界，所以命中串的**前缀**可能已经流出 ——
 * 承诺的对象是整词，不是前缀。
 *
 * <p>生效窗口默认**按词表里最长的敏感词自动抬高**（见 {@link SafetyProperties.Streaming#autoWindow()}），
 * 所以「词表里放了一个比窗口还长的词」不会留下「先推送、后拦截」的缝；只有病态超长（超过 {@code max-auto-window}
 * 上限）才会退回「最终整段审核兜底」，并在启动时记 WARN，不静默。
 *
 * <p>窗口的代价是最多延迟 holdBack 个字符的输出。对逐字生成的流来说这是几十毫秒量级，换来的是审核真正生效。
 */
@Component
public class StreamingSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(StreamingSafetyGuard.class);

    private final ContentSafety contentSafety;
    private final SafetyProperties properties;
    /** 本次装配真正生效的保留窗口（= 承诺「不会被先推送」的命中长度上限）。 */
    private final int window;

    public StreamingSafetyGuard(ContentSafety contentSafety, SafetyProperties properties) {
        this.contentSafety = contentSafety;
        this.properties = properties;
        this.window = resolveWindow(properties, contentSafety);
    }

    /**
     * 算出真正生效的窗口。规则：
     * <ol>
     *   <li>手动档（{@code auto-window=false}）：就是配置值，词更长也照旧 —— 此时那句「拦不住」的 WARN 仍然要打；</li>
     *   <li>自动档（默认）：取「配置值与最长敏感词长度」的较大值，但不超过 {@code max-auto-window}；</li>
     *   <li>词长超过上限：窗口取上限并 WARN —— 明说「哪些长度拦不住」比悄悄只保证一半强。</li>
     * </ol>
     */
    private static int resolveWindow(SafetyProperties properties, ContentSafety contentSafety) {
        SafetyProperties.Streaming streaming = properties.streaming();
        int configured = streaming.holdBackChars();
        int longest = contentSafety.longestKeywordLength();
        if (!streaming.enabled()) {
            return configured;
        }
        if (!streaming.autoWindow()) {
            if (longest > configured) {
                log.warn("流式审核的保留窗口 {} 字符小于最长敏感词 {} 字符（auto-window=false，不做自动抬高）：",
                        configured, longest);
                log.warn("超过窗口的命中无法在推送前拦住，只能靠完整回复的最终审核兜底");
            }
            return configured;
        }
        if (longest > streaming.maxAutoWindow()) {
            log.warn("最长敏感词 {} 字符超过自动窗口上限 {}（aiflow.safety.streaming.max-auto-window）：",
                    longest, streaming.maxAutoWindow());
            log.warn("窗口取上限，超过 {} 字符的命中只能靠完整回复的最终审核兜底；把那项调大即可全拦住",
                    streaming.maxAutoWindow());
            return Math.max(configured, streaming.maxAutoWindow());
        }
        if (longest > configured) {
            log.info("流式审核的保留窗口按最长敏感词自动抬高: {} -> {}（auto-window=false 可关掉这个行为）",
                    configured, longest);
        }
        return Math.max(configured, longest);
    }

    /** 当前生效的保留窗口（诊断与测试用）。 */
    public int window() {
        return window;
    }

    /** 为一次流式回复开一个审核会话：片段从 {@code accept} 进，通过检查的从 {@code sink} 出。 */
    public Session open(Consumer<String> sink) {
        return new Session(contentSafety, properties.streaming(), window, sink);
    }

    /** 一次流式回复的审核状态。非线程安全：它天然属于「一条回复」这一个执行者。 */
    public static final class Session {

        private final ContentSafety contentSafety;
        private final SafetyProperties.Streaming config;
        private final int window;
        private final Consumer<String> sink;
        private final StringBuilder pending = new StringBuilder();

        /** 上一段已放行内容的末尾 (holdBack-1) 个字符：下次检查要从它开始，防止命中串跨段漏过。 */
        private String tail = "";
        private ContentSafety.Verdict verdict = ContentSafety.Verdict.allow();
        private boolean finished;

        private Session(ContentSafety contentSafety, SafetyProperties.Streaming config, int window,
                        Consumer<String> sink) {
            this.contentSafety = contentSafety;
            this.config = config;
            this.window = window;
            this.sink = sink;
        }

        /** 接收模型推来的一个片段。命中之后不再放行任何内容。 */
        public void accept(String chunk) {
            if (finished || !verdict.allowed() || chunk == null || chunk.isEmpty()) {
                return;
            }
            if (!config.enabled()) {
                sink.accept(chunk);
                return;
            }
            pending.append(chunk);
            int safeLength = pending.length() - window;
            if (safeLength > 0) {
                flush(safeLength);
            }
        }

        /**
         * 流结束：把保留窗口里剩下的内容过最后一遍检查再放行。
         * 不调用它，最后 holdBack 个字符会被永远留在缓冲里（表现为回复结尾缺一截）。
         */
        public void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if (!config.enabled() || !verdict.allowed()) {
                return;
            }
            flush(pending.length());
        }

        public boolean blocked() {
            return !verdict.allowed();
        }

        public ContentSafety.Verdict verdict() {
            return verdict;
        }

        private void flush(int length) {
            if (length <= 0) {
                return;
            }
            String candidate = pending.substring(0, length);
            ContentSafety.Verdict fresh = contentSafety.scanRules(tail + candidate);
            if (!fresh.allowed()) {
                verdict = fresh;
                return;
            }
            sink.accept(candidate);
            pending.delete(0, length);
            int overlap = window - 1;
            String combined = tail + candidate;
            tail = combined.length() <= overlap ? combined : combined.substring(combined.length() - overlap);
        }
    }
}
