package com.aiflow.engine.memory;

import com.aiflow.engine.provider.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配口径的纯单元用例（不碰 Spring / DB）：窗口怎么截、摘要摆哪、关掉时是什么样。
 *
 * <p>这些断言看着琐碎，但每一条都对应一种真实退化：摘要被模型当成新指令、旧消息把新消息挤掉、
 * 关掉记忆的工作流仍然带着历史、读失败把用户的正常问答变成 500。
 */
class SessionContextProviderTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final MemoryProperties ON = new MemoryProperties(true, 8, 4000, true, 6, 8000, 1200, true, 5);

    @Test
    void putsSummaryFirstThenHistoryThenCurrentInput() {
        FakePort port = new FakePort()
                .history(ChatMessage.user("第一句"), ChatMessage.assistant("第一句的回答"))
                .summary("用户想要一份离线部署方案");

        List<ChatMessage> messages = provider(port, ON).assemble(SESSION, ChatMessage.user("第二句"),
                HistoryOptions.inherit(8));

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content())
                .as("摘要必须带标签：不然模型会把压缩后的旧内容当成用户当前说的")
                .startsWith(SessionContextProvider.SUMMARY_LABEL).contains("离线部署方案");
        assertThat(messages.subList(1, 3)).extracting(ChatMessage::role).containsExactly("user", "assistant");
        assertThat(messages.get(3).content()).isEqualTo("第二句");
    }

    @Test
    void dropsTheOldestMessagesFirstWhenTheBudgetIsTight() {
        FakePort port = new FakePort().history(
                ChatMessage.user("很老的一句"), ChatMessage.assistant("它的回答"),
                ChatMessage.user("较新的一句"), ChatMessage.assistant("它的回答"));
        // 预算只够最后两条（5 + 4 = 9 字）
        MemoryProperties tight = new MemoryProperties(true, 8, 9, true, 6, 8000, 1200, true, 5);

        List<ChatMessage> messages = provider(port, tight).assemble(SESSION, ChatMessage.user("现在"),
                HistoryOptions.inherit(8));

        assertThat(messages.subList(0, messages.size() - 1)).extracting(ChatMessage::content)
                .as("超预算时丢最老的（与人的短时记忆一致）")
                .containsExactly("较新的一句", "它的回答");
    }

    @Test
    void truncatesASingleOversizedMessageInsteadOfDroppingEverything() {
        FakePort port = new FakePort().history(ChatMessage.user("一二三四五六七八九十"));
        MemoryProperties tiny = new MemoryProperties(true, 8, 4, true, 6, 8000, 1200, true, 5);

        List<ChatMessage> messages = provider(port, tiny).assemble(SESSION, ChatMessage.user("现在"),
                HistoryOptions.inherit(8));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).as("单条就超预算时截断，而不是整条丢").hasSize(4);
    }

    @Test
    void historyCanBeTurnedOffPerNode() {
        FakePort port = new FakePort().history(ChatMessage.user("不该被带上的历史")).summary("也不该被带上");

        List<ChatMessage> messages = provider(port, ON).assemble(SESSION, ChatMessage.user("只发这一句"),
                HistoryOptions.parse("none", null, 8));

        assertThat(messages).containsExactly(new ChatMessage("user", "只发这一句"));
        assertThat(port.historyReads.get()).as("关掉之后连读都不该读").isZero();
    }

    @Test
    void skipsSummaryWhenSummaryIsDisabled() {
        FakePort port = new FakePort().history(ChatMessage.user("历史")).summary("摘要（摘要关着时不该出现）");
        MemoryProperties summaryOff = new MemoryProperties(true, 8, 4000, false, 6, 8000, 1200, true, 5);

        List<ChatMessage> messages = provider(port, summaryOff).assemble(SESSION, ChatMessage.user("现在"),
                HistoryOptions.inherit(8));

        assertThat(messages).noneMatch(message -> "system".equals(message.role()));
        assertThat(port.summaryReads.get()).as("摘要开关关着时不该读摘要表").isZero();
    }

    @Test
    void degradesToNoHistoryWhenThePortBlows() {
        FakePort port = new FakePort().explode();

        List<ChatMessage> messages = provider(port, ON).assemble(SESSION, ChatMessage.user("照常提问"),
                HistoryOptions.inherit(8));

        assertThat(messages).as("读记忆炸了也要照常提问：记忆是增强不是主链路依赖")
                .containsExactly(new ChatMessage("user", "照常提问"));
    }

    @Test
    void wholeFeatureSwitchTurnsEverythingOff() {
        FakePort port = new FakePort().history(ChatMessage.user("历史")).summary("摘要");
        MemoryProperties off = new MemoryProperties(false, 8, 4000, true, 6, 8000, 1200, true, 5);

        assertThat(provider(port, off).assemble(SESSION, ChatMessage.user("现在"), HistoryOptions.inherit(8)))
                .containsExactly(new ChatMessage("user", "现在"));
        assertThat(port.historyReads.get()).isZero();
    }

    @Test
    void parsesNodeParametersWithoutBeingClever() {
        assertThat(HistoryOptions.parse(null, null, 8)).isEqualTo(new HistoryOptions(true, 8, true));
        assertThat(HistoryOptions.parse("none", "false", 8)).isEqualTo(new HistoryOptions(false, 8, false));
        assertThat(HistoryOptions.parse("3", null, 8)).isEqualTo(new HistoryOptions(true, 3, true));
        assertThat(HistoryOptions.parse("拼错了", null, 8))
                .as("无法识别的值按 inherit：拼错的参数不该静默关掉记忆")
                .isEqualTo(new HistoryOptions(true, 8, true));
    }

    @Test
    void propertiesClampNonsenseValues() {
        MemoryProperties clamped = new MemoryProperties(true, -1, -1, true, 0, 0, 0, true, 0);
        assertThat(clamped.windowSize()).isZero();
        assertThat(clamped.summaryChunkSize()).isEqualTo(1);
        assertThat(clamped.summaryMaxAttempts()).isEqualTo(1);
        assertThat(clamped.summaryOff()).isFalse();
        assertThat(new MemoryProperties(true, 8, 4000, false, 6, 8000, 1200, true, 5).summaryOff()).isTrue();
    }

    private static SessionContextProvider provider(SessionMemoryPort port, MemoryProperties properties) {
        return new SessionContextProvider(port, properties);
    }

    /** 测试用端口：想返回什么就返回什么，想炸就炸。 */
    private static final class FakePort implements SessionMemoryPort {

        private final List<ChatMessage> history = new ArrayList<>();
        private String summary;
        private boolean explode;
        private final AtomicInteger historyReads = new AtomicInteger();
        private final AtomicInteger summaryReads = new AtomicInteger();

        FakePort history(ChatMessage... messages) {
            this.history.addAll(List.of(messages));
            return this;
        }

        FakePort summary(String text) {
            this.summary = text;
            return this;
        }

        FakePort explode() {
            this.explode = true;
            return this;
        }

        @Override
        public List<ChatMessage> history(UUID sessionId, int limit) {
            historyReads.incrementAndGet();
            if (explode) {
                throw new IllegalStateException("数据库连不上");
            }
            return List.copyOf(history);
        }

        @Override
        public Optional<String> summary(UUID sessionId) {
            summaryReads.incrementAndGet();
            if (explode) {
                throw new IllegalStateException("数据库连不上");
            }
            return Optional.ofNullable(summary);
        }
    }
}
