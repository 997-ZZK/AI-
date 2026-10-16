package com.aiflow.engine.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    @Test
    void tracksMarkdownSectionPath() {
        TextChunker chunker = new TextChunker(60, 10);
        String text = "# 员工手册\n\n## 报销\n"
                + "差旅住宿标准每晚五百元，需要提供发票，发票抬头必须为公司全称并注明税号，电子发票另附报销封面。\n\n"
                + "## 考勤\n"
                + "上班时间为九点三十分，弹性到十点，每天需要在考勤系统打卡两次，忘记打卡当月可以申诉两次。";

        List<ChunkDraft> drafts = chunker.chunk(text);

        assertThat(drafts).isNotEmpty();
        assertThat(drafts.get(0).sectionPath()).isEqualTo("员工手册 > 报销");
        assertThat(drafts.get(0).content()).contains("五百元").contains("# 员工手册");
        assertThat(drafts).anySatisfy(draft -> {
            assertThat(draft.sectionPath()).isEqualTo("员工手册 > 考勤");
            assertThat(draft.content()).contains("九点三十分");
        });
    }

    @Test
    void dropsTrailingChunkMadeOfOverlapOnly() {
        TextChunker chunker = new TextChunker(50, 5);

        List<ChunkDraft> drafts = chunker.chunk("长".repeat(150));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).charCount()).isEqualTo(150);
    }

    @Test
    void splitsLongTextIntoBoundedChunks() {
        TextChunker chunker = new TextChunker(100, 20);
        String text = ("第一段内容。".repeat(20)) + "\n\n" + ("第二段内容。".repeat(20));

        List<ChunkDraft> drafts = chunker.chunk(text);

        assertThat(drafts.size()).isGreaterThan(1);
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.charCount()).isLessThanOrEqualTo(300);
            assertThat(draft.contentHash()).hasSize(64);
        });
    }

    @Test
    void keepsOverlapBetweenConsecutiveChunks() {
        TextChunker chunker = new TextChunker(120, 30);
        String text = "甲".repeat(200) + "\n\n" + "乙".repeat(200);

        List<ChunkDraft> drafts = chunker.chunk(text);

        assertThat(drafts.size()).isGreaterThan(1);
        String first = drafts.get(0).content();
        String expectedOverlap = first.substring(first.length() - 30).stripLeading();
        assertThat(drafts.get(1).content()).startsWith(expectedOverlap);
    }

    @Test
    void hardSplitsSingleVeryLongSentence() {
        TextChunker chunker = new TextChunker(50, 5);
        String text = "长".repeat(400);

        List<ChunkDraft> drafts = chunker.chunk(text);

        assertThat(drafts).hasSizeGreaterThan(1);
        // 上界 = 单片硬切上限（3 * target）+ 重叠尾巴 + 拼接换行
        int maxChunkChars = 3 * 50 + 5 + 1;
        assertThat(drafts).allSatisfy(draft -> assertThat(draft.charCount()).isLessThanOrEqualTo(maxChunkChars));
    }

    @Test
    void chunkPagesKeepsPageNumbersAndSkipsBlankPages() {
        TextChunker chunker = new TextChunker(30, 5);

        List<ChunkDraft> drafts = chunker.chunkPages(List.of(
                new PageText(1, "第一页的内容，讲报销需要的材料。"),
                new PageText(2, "   "),
                new PageText(3, "第三页的内容，讲审批流程与时限。")));

        assertThat(drafts).isNotEmpty();
        assertThat(drafts).allSatisfy(draft -> assertThat(draft.pageFrom()).isEqualTo(draft.pageTo()));
        assertThat(drafts).anySatisfy(draft -> {
            assertThat(draft.pageFrom()).isEqualTo(1);
            assertThat(draft.content()).contains("报销");
        });
        assertThat(drafts).anySatisfy(draft -> {
            assertThat(draft.pageFrom()).isEqualTo(3);
            assertThat(draft.content()).contains("审批");
        });
        // 空白页不产出切片，但页码序号要连续（否则引用对不上）
        assertThat(drafts).noneMatch(draft -> draft.pageFrom() == 2);
        assertThat(drafts).extracting(ChunkDraft::index).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, drafts.size()).boxed().toList());
    }

    @Test
    void ignoresBlankInputAndValidatesParams() {
        TextChunker chunker = new TextChunker();
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("   \n  ")).isEmpty();
        assertThatThrownBy(() -> new TextChunker(100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
