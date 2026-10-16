package com.aiflow.engine.retrieval.eval;

import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.integration.HttpEmbeddingProvider;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.RagRetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 embedding provider 的阈值重校准（默认跳过，只有显式指定 provider 时才跑）。
 *
 * <p>为什么必须重校准：mock embedding 是确定性哈希，只对词面重叠敏感；真实模型对同义改写也给高分，
 * 余弦分布整体上移、量纲不同。把 mock 量纲下校准出来的 min-score 直接搬到真实模型上，
 * 「低相似度丢弃」会失效（该丢的丢不掉），拒答正确率随之崩掉。
 *
 * <p>跑法（provider 不在线就不要跑，测试会以探活失败直接报错，不会静默跳过）：
 * <pre>
 * mvn test -Dtest=RagRealEmbeddingCalibrationTest \
 *     -Daiflow.eval.embedding.mode=http \
 *     -Daiflow.eval.embedding.base-url=http://localhost:11434/v1 \
 *     -Daiflow.eval.embedding.model=bge-m3
 * </pre>
 *
 * <p>报告写 target/eval/rag-report-&lt;providerId&gt;.json，含完整阈值曲线与每条问题的失败明细；
 * 扫出来的建议阈值要人工复核后写进 docs/quality-plan.md，再同步 {@code aiflow.retrieval.min-score}。
 * 密钥只从 env {@code AIFLOW_EVAL_EMBEDDING_API_KEY}（或系统属性）读，既不进报告、也不进日志。
 */
@EnabledIfSystemProperty(named = "aiflow.eval.embedding.mode", matches = "http")
class RagRealEmbeddingCalibrationTest {

    private static final double RECALL_GATE = 0.85;
    private static final double MRR_GATE = 0.70;
    private static final double REJECTION_GATE = 0.80;
    private static final int DIMENSIONS = 1024;
    private static final Path REPORT_DIR = Path.of("target", "eval");

    /** 真实模型的余弦分布整体高于 mock，网格要往高阈值方向铺开。 */
    private static final List<Double> DEFAULT_SWEEP =
            List.of(0.0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70);

    @Test
    void calibratesThresholdAgainstRealEmbeddingService() throws IOException {
        String baseUrl = property("aiflow.eval.embedding.base-url", "http://localhost:11434/v1");
        String model = property("aiflow.eval.embedding.model", "bge-m3");
        List<Double> sweep = sweep(property("aiflow.eval.threshold-sweep", null));

        EmbeddingProvider provider = new HttpEmbeddingProvider(new RetrievalProperties.Embedding(
                "http", -1, DIMENSIONS, model, baseUrl, apiKey(), 60_000, List.of()));

        // 先探活：服务没起、模型没拉、维度对不上时，这里就给出可判断的失败信息，
        // 而不是在几百次检索之后报一个看不懂的空指针。
        float[] probe = provider.embed("检索链探活");
        assertThat(probe)
                .as("provider %s 返回 %d 维向量，与 vector(%d) 列不一致：换模型前先确认维度或改迁移",
                        provider.id(), probe.length, DIMENSIONS)
                .hasSize(DIMENSIONS);

        RagEvalHarness.GoldenSet goldenSet = RagEvalHarness.GoldenSet.load();
        RagEvalHarness.CachingEmbeddingProvider embeddings =
                new RagEvalHarness.CachingEmbeddingProvider(provider);
        RagRetrievalService service = RagEvalHarness.service(embeddings, goldenSet.documents());

        Map<Double, RagEvalHarness.EvalReport> curve = new LinkedHashMap<>();
        Map<Double, List<RagEvalHarness.QuestionOutcome>> details = new LinkedHashMap<>();
        for (double minScore : sweep) {
            List<RagEvalHarness.QuestionOutcome> evaluated =
                    RagEvalHarness.evaluate(service, goldenSet.questions(), minScore);
            details.put(minScore, evaluated);
            curve.put(minScore, RagEvalHarness.EvalReport.of(evaluated));
        }

        // 达标区间：max 是「仍然满足全部门禁的最大阈值」，也就是在不牺牲 recall / 拒答的前提下能丢的最多噪声。
        Double passingLower = null;
        Double passingUpper = null;
        for (Map.Entry<Double, RagEvalHarness.EvalReport> entry : curve.entrySet()) {
            if (passes(entry.getValue())) {
                if (passingLower == null) {
                    passingLower = entry.getKey();
                }
                passingUpper = entry.getKey();
            }
        }

        double reported = passingUpper == null ? sweep.get(0) : passingUpper;
        Path reportPath = REPORT_DIR.resolve(RagEvalHarness.reportFileName(provider.id()));
        RagEvalHarness.writeReport(reportPath, provider.id(), reported, curve.get(reported),
                details.get(reported), curve);

        print(curve, passingLower, passingUpper, provider.id(), embeddings.cachedTexts());

        assertThat(goldenSet.documents()).hasSizeGreaterThanOrEqualTo(9);
        assertThat(curve.get(reported).answerable()).isGreaterThanOrEqualTo(50);
        assertThat(passingUpper)
                .as("provider %s 在 %s 的任一阈值下都不满足门禁（recall@5>=%s / MRR>=%s / 拒答>=%s）："
                                + "换模型或先改切片与提示词，明细见 %s",
                        provider.id(), sweep, RECALL_GATE, MRR_GATE, REJECTION_GATE, reportPath)
                .isNotNull();
    }

    private static boolean passes(RagEvalHarness.EvalReport report) {
        return report.recallAt5() >= RECALL_GATE
                && report.mrr() >= MRR_GATE
                && report.rejectionAccuracy() >= REJECTION_GATE;
    }

    private static void print(Map<Double, RagEvalHarness.EvalReport> curve, Double lower, Double upper,
                              String providerId, int cachedQueries) {
        System.out.printf("%nembedding calibration  provider=%s  cachedQueries=%d%n", providerId, cachedQueries);
        System.out.println("minScore | recall@5 |    mrr   | reject | empty | pass");
        for (Map.Entry<Double, RagEvalHarness.EvalReport> entry : curve.entrySet()) {
            RagEvalHarness.EvalReport row = entry.getValue();
            System.out.printf("%8.2f | %8.4f | %8.4f | %6.4f | %5d | %s%n",
                    entry.getKey(), row.recallAt5(), row.mrr(), row.rejectionAccuracy(), row.answerableEmpty(),
                    passes(row) ? "yes" : "-");
        }
        if (upper == null) {
            System.out.println("=> no threshold passes the gates; the corpora or the provider must change first");
            return;
        }
        System.out.printf("=> passing range [%.2f, %.2f]; set aiflow.retrieval.min-score=%.2f "
                + "(max still passing, i.e. noise dropped hardest without losing recall)%n", lower, upper, upper);
    }

    private static List<Double> sweep(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SWEEP;
        }
        List<Double> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            if (!token.isBlank()) {
                values.add(Double.parseDouble(token.strip()));
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("aiflow.eval.threshold-sweep 解析为空: " + raw);
        }
        return List.copyOf(values);
    }

    private static String property(String name, String fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 密钥优先取系统属性，其次取环境变量（环境变量不会出现在命令行与进程参数里）。 */
    private static String apiKey() {
        String fromProperty = System.getProperty("aiflow.eval.embedding.api-key");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnv = System.getenv("AIFLOW_EVAL_EMBEDDING_API_KEY");
        return fromEnv == null ? "" : fromEnv;
    }
}