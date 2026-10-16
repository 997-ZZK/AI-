package com.aiflow.engine.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievedChunkTest {

    @Test
    void citationLabelKeepsTitleAndSection() {
        RetrievedChunk chunk = chunk("报销制度.md", "发票要求 > 补充说明", null, null);

        assertThat(chunk.citationLabel()).isEqualTo("报销制度.md > 发票要求 > 补充说明");
    }

    @Test
    void citationLabelDropsSectionSegmentRepeatingTheTitle() {
        assertThat(chunk("报销制度.md", "报销制度 > 发票要求", null, null).citationLabel())
                .as("markdown 的 H1 会进标题栈，引用里不该出现「标题 > 标题 > 章节」")
                .isEqualTo("报销制度.md > 发票要求");
        assertThat(chunk("报销制度.md", "报销制度", null, null).citationLabel())
                .isEqualTo("报销制度.md");
        assertThat(chunk("报销制度.md", "报销制度.md > 差旅标准", null, null).citationLabel())
                .isEqualTo("报销制度.md > 差旅标准");
    }

    @Test
    void citationLabelKeepsUnrelatedSectionAndPages() {
        assertThat(chunk("手册.md", "考勤 > 打卡", 3, 4).citationLabel())
                .isEqualTo("手册.md > 考勤 > 打卡 p.3-4");
    }

    @Test
    void mergedChunkTakesPageUnionAndHigherScore() {
        RetrievedChunk first = new RetrievedChunk("local", "doc-a", "手册", "c0", 0, "章节", 2, 3,
                "第一段", "hash-1", 0.4, 0.4, null);
        RetrievedChunk second = new RetrievedChunk("local", "doc-a", "手册", "c1", 1, "章节", 4, 5,
                "第二段", "hash-2", 0.7, 0.7, null);

        RetrievedChunk merged = first.mergedWith(second, "第一段\n第二段");

        assertThat(merged.text()).isEqualTo("第一段\n第二段");
        assertThat(merged.pageFrom()).isEqualTo(2);
        assertThat(merged.pageTo()).isEqualTo(5);
        assertThat(merged.score()).isEqualTo(0.7);
        assertThat(merged.sameDocument(second)).isTrue();
    }

    @Test
    void citationLabelFallsBackToDocIdWhenTitleIsMissing() {
        assertThat(chunk(null, "章节", null, null).citationLabel()).isEqualTo("doc-a > 章节");
    }

    private static RetrievedChunk chunk(String title, String sectionPath, Integer pageFrom, Integer pageTo) {
        return new RetrievedChunk("local", "doc-a", title, "c0", 0, sectionPath, pageFrom, pageTo,
                "内容", "hash", 0.5, 0.5, null);
    }
}