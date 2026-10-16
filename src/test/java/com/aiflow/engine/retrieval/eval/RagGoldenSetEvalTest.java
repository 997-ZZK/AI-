package com.aiflow.engine.retrieval.eval;

import com.aiflow.engine.retrieval.RagRetrievalService;
import com.aiflow.engine.retrieval.mock.MockEmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 黄金集质量门禁（口径见 docs/quality-plan.md）。
 *
 * <p>指标：recall@5 &gt;= 0.85、MRR &gt;= 0.70、拒答正确率 &gt;= 0.80。
 * 数据：src/test/resources/eval/rag-golden-set.jsonl（9 篇文档 / 55 条可答 / 11 条不可答，不可答含域内与域外两类）。
 * 链路：DocumentParser -&gt; TextChunker -&gt; mock embedding -&gt; local provider -&gt;
 * RagRetrievalService（归一化 -&gt; 阈值 -&gt; 断崖裁剪 -&gt; MMR -&gt; 相邻合并）-&gt; 引用判定。
 *
 * <p>用确定性 mock embedding 跑，不烧钱、不依赖网络；MIN_SCORE 是它的量纲下的校准值（见常量注释），
 * 换真实 embedding provider 后必须重新校准：真实 provider 的曲线由
 * {@link RagRealEmbeddingCalibrationTest} 产出，新口径写回 docs/quality-plan.md。
 * 报告里同时写入阈值曲线（各 minScore 下的指标），便于复现校准过程；
 * 明细写 target/eval/rag-report.json：断言失败先看报告，再判断是切片、阈值还是排序的问题。
 */
class RagGoldenSetEvalTest {

    private static final String EMBEDDING_ID = "mock-embedding";
    private static final int EMBEDDING_DIMENSIONS = 1024;

    /**
     * mock embedding 只对词面重叠敏感，余弦整体比真实模型低（同义改写不加分），
     * 阈值必须按它的量纲校准：曲线在 target/eval/rag-report.json 的 thresholdCurve 里，
     * 取「recall 已饱和、拒答仍成立」的位置并留出余量。
     */
    private static final double MIN_SCORE = 0.15;

    /** 校准用的阈值网格：门禁只取 MIN_SCORE 那一行，其余行用于复盘与回归对比。 */
    private static final List<Double> THRESHOLD_SWEEP =
            List.of(0.0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35);

    private static final double RECALL_GATE = 0.85;
    private static final double MRR_GATE = 0.70;
    private static final double REJECTION_GATE = 0.80;
    private static final Path REPORT_PATH = Path.of("target", "eval", "rag-report.json");

    @Test
    void meetsQualityGatesOnGoldenSet() throws IOException {
        RagEvalHarness.GoldenSet goldenSet = RagEvalHarness.GoldenSet.load();
        RagRetrievalService service = RagEvalHarness.service(
                new RagEvalHarness.CachingEmbeddingProvider(new MockEmbeddingProvider(EMBEDDING_ID,
                        EMBEDDING_DIMENSIONS)),
                goldenSet.documents());

        Map<Double, RagEvalHarness.EvalReport> curve = new LinkedHashMap<>();
        List<RagEvalHarness.QuestionOutcome> outcomes = null;
        for (double minScore : THRESHOLD_SWEEP) {
            List<RagEvalHarness.QuestionOutcome> evaluated =
                    RagEvalHarness.evaluate(service, goldenSet.questions(), minScore);
            curve.put(minScore, RagEvalHarness.EvalReport.of(evaluated));
            if (Math.abs(minScore - MIN_SCORE) < 1e-9) {
                outcomes = evaluated;
            }
        }
        if (outcomes == null) {
            throw new IllegalStateException("MIN_SCORE 必须落在 THRESHOLD_SWEEP 网格里: " + MIN_SCORE);
        }
        RagEvalHarness.EvalReport report = curve.get(MIN_SCORE);
        RagEvalHarness.writeReport(REPORT_PATH, EMBEDDING_ID + "/" + EMBEDDING_DIMENSIONS, MIN_SCORE, report,
                outcomes, curve);
        String diagnostics = report.diagnostics(outcomes);

        assertThat(goldenSet.documents()).hasSizeGreaterThanOrEqualTo(9);
        assertThat(report.answerable()).isGreaterThanOrEqualTo(50);
        assertThat(report.unanswerable()).isGreaterThanOrEqualTo(10);
        assertThat(report.recallAt5())
                .as("recall@5 低于门槛，明细见 target/eval/rag-report.json: " + diagnostics)
                .isGreaterThanOrEqualTo(RECALL_GATE);
        assertThat(report.mrr())
                .as("MRR 低于门槛，明细见 target/eval/rag-report.json: " + diagnostics)
                .isGreaterThanOrEqualTo(MRR_GATE);
        assertThat(report.rejectionAccuracy())
                .as("拒答正确率低于门槛，明细见 target/eval/rag-report.json: " + diagnostics)
                .isGreaterThanOrEqualTo(REJECTION_GATE);
    }
}