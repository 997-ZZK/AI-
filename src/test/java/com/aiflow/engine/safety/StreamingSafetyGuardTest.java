package com.aiflow.engine.safety;

import com.aiflow.engine.config.SafetyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式增量审核：片段在被推送之前过规则。
 *
 * <p>测试的断言点只有一句话：**完整命中串不能出现在已推送的内容里**（它的前缀可能已经流出，那是不可撤回的部分）。
 * 以及它的对偶：正常文本必须**一字不差**地送达下游，不能因为加了一层审核就丢字或乱序。
 */
class StreamingSafetyGuardTest {

    private static final String KEYWORD = "制作炸弹";

    private static StreamingSafetyGuard guard(int holdBack) {
        return new StreamingSafetyGuard(new ContentSafety(new SafetyProperties(true, 4_000, 8_000, "预设话术",
                List.of(KEYWORD, "违规词"), "", List.of(), new SafetyProperties.Streaming(true, holdBack, true, 200)),
                new DefaultResourceLoader()),
                new SafetyProperties(true, 4_000, 8_000, "预设话术", List.of(KEYWORD, "违规词"), "", List.of(),
                        new SafetyProperties.Streaming(true, holdBack, true, 200)));
    }

    private static StreamingSafetyGuard guard(boolean enabled, int holdBack) {
        SafetyProperties properties = new SafetyProperties(true, 4_000, 8_000, "预设话术", List.of(KEYWORD), "",
                List.of(), new SafetyProperties.Streaming(enabled, holdBack, true, 200));
        return new StreamingSafetyGuard(new ContentSafety(properties, new DefaultResourceLoader()), properties);
    }

    @Test
    void deliversCleanTextUnchangedChunkByChunk() {
        Collector sink = new Collector();
        StreamingSafetyGuard.Session session = guard(32).open(sink);
        String text = "今天天气不错，适合出门走走。这是一段足够长的正常回复，用来验证切片之后不会丢字。";

        for (int index = 0; index < text.length(); index += 7) {
            session.accept(text.substring(index, Math.min(text.length(), index + 7)));
        }
        session.finish();

        assertThat(sink.text.toString()).isEqualTo(text);
        assertThat(session.blocked()).isFalse();
    }

    @Test
    void blocksKeywordBeforeItReachesTheSink() {
        Collector sink = new Collector();
        StreamingSafetyGuard.Session session = guard(32).open(sink);

        session.accept("这是一段正常的开场白。" + KEYWORD + "的方法如下，先准备一些材料。");
        session.finish();

        assertThat(sink.text.toString()).doesNotContain(KEYWORD);
        assertThat(session.blocked()).isTrue();
        assertThat(session.verdict().category()).isEqualTo("敏感词");
    }

    @Test
    void blocksKeywordSplitAcrossChunks() {
        Collector sink = new Collector();
        StreamingSafetyGuard.Session session = guard(32).open(sink);

        session.accept("先聊点别的吧，然后我们说说这个话题。");
        session.accept("制");
        session.accept("作");
        session.accept("炸");
        session.accept("弹");
        session.accept("的配方在哪里。");
        session.finish();

        assertThat(sink.text.toString()).doesNotContain(KEYWORD);
        assertThat(session.blocked()).isTrue();
    }

    @Test
    void blocksKeywordSittingExactlyOnTheWindowBoundary() {
        // 保留窗口 4、关键词 4：命中串的最后一个字落在窗口里，前三个字已经被放行过一次 ——
        // 这正是「重叠窗口」要解决的场景：不放回看 (holdBack-1) 个字符就会漏。
        Collector sink = new Collector();
        StreamingSafetyGuard.Session session = guard(4).open(sink);

        for (String chunk : List.of("ABCD", "制", "作", "炸", "弹", "EFGH")) {
            session.accept(chunk);
        }
        session.finish();

        assertThat(sink.text.toString()).doesNotContain(KEYWORD);
        assertThat(session.blocked()).isTrue();
    }

    @Test
    void holdsBackTheTailUntilFinish() {
        Collector sink = new Collector();
        StreamingSafetyGuard.Session session = guard(32).open(sink);

        session.accept("很短的回复");
        assertThat(sink.text.toString())
                .as("短于保留窗口的内容先不发：发出去就收不回来了")
                .isEmpty();

        session.finish();
        assertThat(sink.text.toString()).isEqualTo("很短的回复");
    }

    @Test
    void pushesNothingMoreOnceBlocked() {
        Collector sink = new Collector();
        StreamingSafetyGuard.Session session = guard(32).open(sink);

        session.accept("来点什么" + KEYWORD + "吧。");
        int afterBlock = sink.chunks.size();
        session.accept("后续内容也一起拦掉。");
        session.finish();

        assertThat(session.blocked()).isTrue();
        assertThat(sink.chunks.size()).isEqualTo(afterBlock);
        assertThat(sink.text.toString()).doesNotContain(KEYWORD);
    }

    @Test
    void disabledStreamingIsAPassthrough() {
        Collector sink = new Collector();
        StreamingSafetyGuard.Session session = guard(false, 32).open(sink);

        session.accept(KEYWORD + "也可以直接过去");
        session.finish();

        assertThat(sink.text.toString()).contains(KEYWORD);
        assertThat(session.blocked()).isFalse();
    }

    @Test
    void finishIsIdempotentAndIgnoresEmptyChunks() {
        Collector sink = new Collector();
        StreamingSafetyGuard.Session session = guard(32).open(sink);

        session.accept(null);
        session.accept("");
        session.accept("正常内容");
        session.finish();
        session.finish();

        assertThat(sink.text.toString()).isEqualTo("正常内容");
    }

    // ---- 长敏感词的兜底（窗口 < 词长） ----

    @Test
    void raisesWindowToLongestKeywordSoLongHitsAreNeverPushedFirst() {
        String longKeyword = "敏" + "感".repeat(39);
        StreamingSafetyGuard guard = guard(List.of(longKeyword), 8, true, 200);

        assertThat(guard.window())
                .as("窗口自动抬到最长敏感词长度：否则这个词会「先推送、后拦截」")
                .isEqualTo(40);

        Collector sink = new Collector();
        StreamingSafetyGuard.Session session = guard.open(sink);
        String text = "先来一段正常开场白。" + longKeyword + "，后面还有正常内容。";
        for (int index = 0; index < text.length(); index += 3) {
            session.accept(text.substring(index, Math.min(text.length(), index + 3)));
        }
        session.finish();

        assertThat(session.blocked()).isTrue();
        // 承诺是「整词不会被推出去」；前缀有可能已经流出（放行是按冲刷边界走的，不是按词边界），
        // 所以这里不写「一个字符都不能漏」——那是过度承诺，会变成一条假绿灯的断言。
        assertThat(sink.text.toString()).doesNotContain(longKeyword);
    }

    @Test
    void autoWindowCanBeTurnedOffAndThenLongHitsLeakBeforeBlocking() {
        String longKeyword = "敏" + "感".repeat(39);
        StreamingSafetyGuard guard = guard(List.of(longKeyword), 8, false, 200);

        assertThat(guard.window()).as("手动档就是要原样的窗口，做实验用").isEqualTo(8);

        Collector sink = new Collector();
        StreamingSafetyGuard.Session session = guard.open(sink);
        String text = "开场白" + longKeyword + "结尾";
        for (int index = 0; index < text.length(); index += 3) {
            session.accept(text.substring(index, Math.min(text.length(), index + 3)));
        }
        session.finish();

        assertThat(session.blocked())
                .as("窗口 8 装不下 40 字的词：流式这一层根本来不及看见整词")
                .isFalse();
        assertThat(sink.text.toString())
                .as("整词会一路推到用户面前 —— 这正是「长词必须靠最终整段审核兜底」的含义")
                .contains(longKeyword);
    }

    @Test
    void maxAutoWindowCapsTheWindowForPathologicallyLongKeywords() {
        String absurd = "敏" + "感".repeat(99);

        assertThat(guard(List.of(absurd), 8, true, 16).window()).isEqualTo(16);
        assertThat(guard(List.of(absurd), 8, true, 400).window()).isEqualTo(100);
    }

    @Test
    void windowIsNeverSmallerThanConfiguredOrKeywordLength() {
        assertThat(guard(List.of("制作炸弹"), 32, true, 200).window()).isEqualTo(32);
        assertThat(guard(List.of("制作炸弹"), 4, true, 200).window()).isEqualTo(4);
    }

    /** 指定词表与窗口设置（长词用例要自己造词表）。 */
    private static StreamingSafetyGuard guard(List<String> keywords, int holdBack, boolean autoWindow,
                                              int maxAutoWindow) {
        SafetyProperties properties = new SafetyProperties(true, 4_000, 8_000, "预设话术", keywords, "", List.of(),
                new SafetyProperties.Streaming(true, holdBack, autoWindow, maxAutoWindow));
        return new StreamingSafetyGuard(new ContentSafety(properties, new DefaultResourceLoader()), properties);
    }

    private static final class Collector implements Consumer<String> {

        private final List<String> chunks = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void accept(String chunk) {
            chunks.add(chunk);
            text.append(chunk);
        }
    }
}
