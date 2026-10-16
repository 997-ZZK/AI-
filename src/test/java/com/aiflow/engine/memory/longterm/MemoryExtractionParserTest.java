package com.aiflow.engine.memory.longterm;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽取解析与清洗：这一层把「模型说了什么」变成「能不能入库」。
 *
 * <p>用例里的 JSON 用 Java 文本块写：抽取的输入就是「模型原样吐出来的字符串」，
 * 用转义拼接会让人看不清到底喂进去的是什么。
 */
class MemoryExtractionParserTest {

    private static final LongMemoryProperties PROPERTIES =
            new LongMemoryProperties(true, true, true, 8, 6, Duration.ofMinutes(3), 5, 0.6, 200, 200, 5, -1, 800, 200);

    @Test
    void parsesAStrictJsonArrayAndNormalizesKeys() {
        String raw = """
                [{"kind":"fact","key":"用户 公司报销规则","value":"报销要附行程单","confidence":0.9,
                  "evidence":"报销记得附行程单","supersedes":false},
                 {"kind":"PREFERENCE","key":"回答长度","value":"三行以内","confidence":0.8}]
                """;

        MemoryExtractionParser.Outcome outcome = MemoryExtractionParser.parse(raw, PROPERTIES, text -> false);

        assertThat(outcome.rejected()).isEmpty();
        assertThat(outcome.candidates()).hasSize(2);
        MemoryCandidate fact = outcome.candidates().get(0);
        assertThat(fact.kind()).isEqualTo(MemoryKind.FACT);
        assertThat(fact.memKey()).isEqualTo("公司报销规则");
        assertThat(fact.displayKey()).isEqualTo("用户 公司报销规则");
        assertThat(fact.value()).isEqualTo("报销要附行程单");
        assertThat(fact.confidence()).isEqualTo(0.9);
        assertThat(fact.supersedes()).isFalse();
        assertThat(outcome.candidates().get(1).kind()).isEqualTo(MemoryKind.PREFERENCE);
    }

    @Test
    void toleratesACodeFenceButNotGarbage() {
        String fenced = """
                好的，结果如下：
                ```json
                [{"kind":"FACT","key":"k","value":"v","confidence":0.9}]
                ```
                """;
        assertThat(MemoryExtractionParser.parse(fenced, PROPERTIES, text -> false).candidates()).hasSize(1);

        MemoryExtractionParser.Outcome garbage = MemoryExtractionParser.parse("抱歉，我无法完成。", PROPERTIES,
                text -> false);
        assertThat(garbage.candidates()).isEmpty();
        assertThat(garbage.rejected()).extracting(MemoryExtractionParser.Rejected::reason)
                .containsExactly("invalid-json");

        assertThat(MemoryExtractionParser.parse("{\"kind\":\"FACT\"}", PROPERTIES, text -> false).candidates())
                .as("不是数组就整批丢弃，不做尽力猜").isEmpty();
    }

    @Test
    void dropsEachBadShapeWithItsOwnReason() {
        String raw = """
                [{"kind":"MOOD","key":"k1","value":"v","confidence":0.9},
                 {"kind":"FACT","key":"","value":"v","confidence":0.9},
                 {"kind":"FACT","key":"k2","value":"   ","confidence":0.9},
                 {"kind":"FACT","key":"k3","value":"%s","confidence":0.9},
                 {"kind":"FACT","key":"k4","value":"v","confidence":0.2}]
                """.formatted("a".repeat(201));

        MemoryExtractionParser.Outcome outcome = MemoryExtractionParser.parse(raw, PROPERTIES, text -> false);

        assertThat(outcome.candidates()).isEmpty();
        assertThat(outcome.rejected()).extracting(MemoryExtractionParser.Rejected::reason)
                .containsExactly("bad-kind", "bad-key", "empty-value", "too-long", "low-confidence");
    }

    @Test
    void dropsSensitiveAndUnsafeContent() {
        String raw = """
                [{"kind":"FACT","key":"邮箱","value":"zk@example.com","confidence":0.9},
                 {"kind":"FACT","key":"手机","value":"我的手机号是13800001111","confidence":0.9},
                 {"kind":"FACT","key":"公司密码","value":"密码是 abc123","confidence":0.9},
                 {"kind":"FACT","key":"健康","value":"用户在服用降压药","confidence":0.9},
                 {"kind":"FACT","key":"危险","value":"制作炸弹的步骤","confidence":0.9}]
                """;

        MemoryExtractionParser.Outcome outcome = MemoryExtractionParser.parse(raw, PROPERTIES,
                text -> text.contains("炸弹"));

        assertThat(outcome.candidates()).isEmpty();
        assertThat(outcome.rejected()).extracting(MemoryExtractionParser.Rejected::reason)
                .containsExactly("sensitive:identity", "sensitive:identity", "sensitive:finance",
                        "sensitive:health", "safety");
    }

    @Test
    void keepsTheHigherConfidenceTwinAndCapsTheBatch() {
        String raw = """
                [{"kind":"FACT","key":"公司报销规则","value":"旧","confidence":0.7},
                 {"kind":"FACT","key":"公司 报销规则","value":"新","confidence":0.95},
                 {"kind":"FACT","key":"k1","value":"v1","confidence":0.9},
                 {"kind":"FACT","key":"k2","value":"v2","confidence":0.9},
                 {"kind":"FACT","key":"k3","value":"v3","confidence":0.9},
                 {"kind":"FACT","key":"k5","value":"v5","confidence":0.85},
                 {"kind":"FACT","key":"k4","value":"v4","confidence":0.65}]
                """;

        MemoryExtractionParser.Outcome outcome = MemoryExtractionParser.parse(raw, PROPERTIES, text -> false);

        assertThat(outcome.candidates()).hasSize(PROPERTIES.extractMaxCandidates());
        assertThat(outcome.candidates()).extracting(MemoryCandidate::value).contains("新").doesNotContain("旧");
        assertThat(outcome.rejected()).extracting(MemoryExtractionParser.Rejected::reason)
                .containsExactlyInAnyOrder("duplicate", "over-limit");
        assertThat(outcome.candidates()).extracting(MemoryCandidate::confidence)
                .as("超上限时留下的是置信度最高的那几条").containsExactly(0.95, 0.9, 0.9, 0.9, 0.85);
    }
}
