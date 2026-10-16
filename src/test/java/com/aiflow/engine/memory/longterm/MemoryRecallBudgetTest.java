package com.aiflow.engine.memory.longterm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 注入预算裁剪：按分数累积，放不下就从**最低分**那条开始丢，且不截断。 */
class MemoryRecallBudgetTest {

    @Test
    void keepsEverythingThatFits() {
        MemoryRecallBudget.Trimmed trimmed = MemoryRecallBudget.apply(List.of(hit(0.9, "短"), hit(0.8, "也短")), 800);

        assertThat(trimmed.kept()).hasSize(2);
        assertThat(trimmed.dropped()).isEmpty();
    }

    @Test
    void dropsFromTheLowestScoreWhenTheBudgetRunsOut() {
        MemoryRecallBudget.Trimmed trimmed = MemoryRecallBudget.apply(
                List.of(hit(0.9, "分数最高"), hit(0.7, "中"), hit(0.6, "分数最低")), 35);

        assertThat(trimmed.kept()).extracting(RecalledMemory::value).containsExactly("分数最高", "中");
        assertThat(trimmed.dropped()).extracting(RecalledMemory::value).containsExactly("分数最低");
    }

    @Test
    void neverTruncatesAFactEvenWhenItAloneExceedsTheBudget() {
        MemoryRecallBudget.Trimmed trimmed = MemoryRecallBudget.apply(List.of(hit(0.9, "a".repeat(100))), 10);

        assertThat(trimmed.kept()).as("被切一半的事实比不注入更容易误导，所以整条丢").isEmpty();
        assertThat(trimmed.dropped()).hasSize(1);
    }

    @Test
    void zeroBudgetMeansNoInjection() {
        MemoryRecallBudget.Trimmed trimmed = MemoryRecallBudget.apply(List.of(hit(0.9, "短")), 0);

        assertThat(trimmed.kept()).isEmpty();
        assertThat(trimmed.dropped()).hasSize(1);
    }

    private static RecalledMemory hit(double score, String value) {
        return new RecalledMemory(UUID.randomUUID(), MemoryKind.FACT, "key", value, score);
    }
}
