package com.aiflow.engine.memory.longterm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 归并规则：覆盖与否由「纠正还是新事实」决定，且人工优先。 */
class MemoryMergeTest {

    private static MemoryCandidate candidate(String value, boolean supersedes) {
        return new MemoryCandidate(MemoryKind.FACT, "公司报销规则", "公司报销规则", value, 0.9, "evidence", supersedes);
    }

    @Test
    void createsWhenTheKeyIsNew() {
        assertThat(MemoryMerge.decide(candidate("报销要附行程单", false), null).action())
                .isEqualTo(MemoryMerge.Action.CREATE);
    }

    @Test
    void onlySupersedeOverwritesAnExtractedEntry() {
        MemoryMerge.Existing existing = new MemoryMerge.Existing(MemorySource.EXTRACTED, "报销要附行程单");

        assertThat(MemoryMerge.decide(candidate("报销不用附行程单", true), existing).action())
                .as("模型判定这是纠正 -> 覆盖").isEqualTo(MemoryMerge.Action.UPDATE);
        assertThat(MemoryMerge.decide(candidate("报销不用附行程单", false), existing))
                .as("同一件事但没标纠正 -> 冲突丢弃（宁可留着旧的，也不让模型读错一次就改掉事实）")
                .isEqualTo(new MemoryMerge.Decision(MemoryMerge.Action.REJECT, "conflict"));
    }

    @Test
    void manualEntriesWinOverExtraction() {
        MemoryMerge.Existing existing = new MemoryMerge.Existing(MemorySource.MANUAL, "报销要附行程单和发票");

        assertThat(MemoryMerge.decide(candidate("报销不用附行程单", true), existing))
                .as("人工录入永不被覆盖：连 supersedes=true 也不行").isEqualTo(
                        new MemoryMerge.Decision(MemoryMerge.Action.REJECT, "manual-entry"));
    }

    @Test
    void identicalValueIsNotAnUpdate() {
        MemoryMerge.Existing existing = new MemoryMerge.Existing(MemorySource.EXTRACTED, "报销要附行程单");

        assertThat(MemoryMerge.decide(candidate("报销要附行程单", true), existing))
                .as("一字不差的重抽不该产生一次 UPDATED（否则流水里全是噪声）").isEqualTo(
                        new MemoryMerge.Decision(MemoryMerge.Action.REJECT, "identical-value"));
    }
}
