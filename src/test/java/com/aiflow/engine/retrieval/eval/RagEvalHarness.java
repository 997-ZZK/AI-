package com.aiflow.engine.retrieval.eval;

import com.aiflow.engine.ingest.ChunkDraft;
import com.aiflow.engine.ingest.DocumentParser;
import com.aiflow.engine.ingest.DocumentSourceType;
import com.aiflow.engine.ingest.ParsedDocument;
import com.aiflow.engine.ingest.TextChunker;
import com.aiflow.engine.retrieval.ContextChunk;
import com.aiflow.engine.retrieval.ContextPromptAssembler;
import com.aiflow.engine.retrieval.CosineScoreNormalizer;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.RagRetrievalService;
import com.aiflow.engine.retrieval.RetrievalProvider;
import com.aiflow.engine.retrieval.RetrievalResult;
import com.aiflow.engine.retrieval.RetrievedChunk;
import com.aiflow.engine.retrieval.mock.MockKnowledgeBaseProvider;
import com.aiflow.engine.retrieval.post.RetrievalParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 黄金集评测的可复用骨架：装载语料与问题、按生产口径切片、跑检索链、算指标、写报告。
 *
 * <p>抽出来的目的：同一套切片 / 裁剪 / 判定口径要跑两种 embedding ——
 * mock（离线门禁，随 {@code mvn test} 每次跑）与真实服务（阈值重校准，需要 provider 在线，默认跳过）。
 * 两边共用同一份逻辑，重校准出来的阈值才对得上生产链路。
 */
final class RagEvalHarness {

    static final String KB_ID = "eval-kb";
    static final int RECALL_WINDOW = 5;
    static final int TOP_K = RECALL_WINDOW;

    private RagEvalHarness() {
    }

    /** 按生产默认口径切片（目标 400 字 / 重叠 80 字），保证评测与线上切片策略一致。 */
    static List<RetrievedChunk> corpus(List<GoldenDocument> documents) {
        DocumentParser parser = new DocumentParser(new ObjectMapper());
        TextChunker chunker = new TextChunker();
        List<RetrievedChunk> corpus = new ArrayList<>();
        for (GoldenDocument document : documents) {
            ParsedDocument parsed = parser.parse(document.title(), DocumentSourceType.MARKDOWN, document.content());
            for (ChunkDraft draft : chunker.chunk(parsed.content())) {
                corpus.add(new RetrievedChunk("local", document.docId(), document.title(),
                        document.docId() + "#" + draft.index(), draft.index(), draft.sectionPath(),
                        draft.pageFrom(), draft.pageTo(), draft.content(), draft.contentHash(), 0, 0, null));
            }
        }
        return List.copyOf(corpus);
    }

    /** 语料走本地内存 provider（不落库），只测检索链本身；换 embedding 就是换第一个参数。 */
    static RagRetrievalService service(EmbeddingProvider embeddings, List<GoldenDocument> documents) {
        Map<String, List<RetrievedChunk>> corpusByKb = new LinkedHashMap<>();
        corpusByKb.put(KB_ID, corpus(documents));
        RetrievalProvider local = new MockKnowledgeBaseProvider("local",
                kbId -> corpusByKb.getOrDefault(kbId, List.of()), embeddings, 1.0);
        return new RagRetrievalService(embeddings, List.of(local),
                List.of(new CosineScoreNormalizer("local")), RetrievalParams.defaults(), new ContextPromptAssembler());
    }

    static List<QuestionOutcome> evaluate(RagRetrievalService service, List<GoldenQuestion> questions,
                                          double minScore) {
        List<QuestionOutcome> outcomes = new ArrayList<>(questions.size());
        for (GoldenQuestion question : questions) {
            RetrievalResult result = service.retrieve(question.question(), KB_ID, Set.of("local"), TOP_K, minScore);
            outcomes.add(QuestionOutcome.of(question, result));
        }
        return outcomes;
    }

    static void writeReport(Path path, String embeddingId, double minScore, EvalReport report,
                            List<QuestionOutcome> outcomes, Map<Double, EvalReport> curve) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("generatedAt", Instant.now().toString());

        ObjectNode config = root.putObject("config");
        config.put("kbId", KB_ID);
        config.put("embedding", embeddingId);
        config.put("topK", TOP_K);
        config.put("minScore", minScore);
        config.put("pipeline", "threshold -> cliffTopK(minK=1,maxK=8,cliffRatio=0.35) -> mmr(lambda=0.7) -> mergeAdjacent");

        ObjectNode metrics = root.putObject("metrics");
        metrics.put("answerableQuestions", report.answerable());
        metrics.put("unanswerableQuestions", report.unanswerable());
        metrics.put("goldTargets", report.goldTargets());
        metrics.put("matchedTargets", report.matchedTargets());
        metrics.put("answerableEmpty", report.answerableEmpty());
        metrics.put("recall@5", round(report.recallAt5()));
        metrics.put("mrr", round(report.mrr()));
        metrics.put("rejectionAccuracy", round(report.rejectionAccuracy()));

        ArrayNode thresholdCurve = root.putArray("thresholdCurve");
        for (Map.Entry<Double, EvalReport> entry : curve.entrySet()) {
            EvalReport row = entry.getValue();
            ObjectNode node = thresholdCurve.addObject();
            node.put("minScore", entry.getKey());
            node.put("recall@5", round(row.recallAt5()));
            node.put("mrr", round(row.mrr()));
            node.put("rejectionAccuracy", round(row.rejectionAccuracy()));
            node.put("answerableEmpty", row.answerableEmpty());
            node.put("matchedTargets", row.matchedTargets());
        }

        ArrayNode questions = root.putArray("questions");
        for (QuestionOutcome outcome : outcomes) {
            GoldenQuestion question = outcome.question();
            ObjectNode node = questions.addObject();
            node.put("id", question.id());
            node.put("question", question.question());
            node.put("answerable", question.answerable());
            node.put("empty", outcome.chunks().isEmpty());
            ArrayNode ranks = node.putArray("goldRanks");
            outcome.ranks().forEach(ranks::add);
            ArrayNode chunks = node.putArray("chunks");
            int rank = 1;
            for (RetrievedChunk chunk : outcome.chunks()) {
                ObjectNode view = chunks.addObject();
                view.put("rank", rank++);
                view.put("docId", chunk.docId());
                view.put("sectionPath", chunk.sectionPath());
                view.put("score", round(chunk.score()));
                view.put("citation", chunk.citationLabel());
                view.put("snippet", snippet(chunk.text()));
            }
        }

        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
    }

    static String snippet(String text) {
        String flattened = text == null ? "" : text.replace('\n', ' ').strip();
        return flattened.length() <= 48 ? flattened : flattened.substring(0, 48) + "...";
    }

    static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    /** 报告文件名里带上 provider 标识，避免 mock 与真实 provider 的报告互相覆盖。 */
    static String reportFileName(String embeddingId) {
        String slug = embeddingId.replaceAll("[^A-Za-z0-9._-]", "-");
        return "rag-report-" + slug + ".json";
    }

    /**
     * 给 embedding provider 加一层文本级缓存：阈值扫描要对同一批问题反复检索，
     * 不缓存的话 query 向量会被重算 N 遍（真实 provider 上就是 N 倍调用与费用）。
     * 缓存也保证了扫描各点用的是同一批向量，指标差异只来自阈值。
     */
    static final class CachingEmbeddingProvider implements EmbeddingProvider {

        private final EmbeddingProvider delegate;
        private final Map<String, float[]> cache = new ConcurrentHashMap<>();

        CachingEmbeddingProvider(EmbeddingProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }

        @Override
        public boolean healthy() {
            return delegate.healthy();
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                float[] cached = cache.get(text);
                if (cached == null) {
                    cached = delegate.embed(text);
                    cache.put(text, cached);
                }
                vectors.add(cached);
            }
            return vectors;
        }

        int cachedTexts() {
            return cache.size();
        }
    }

    record GoldenDocument(String docId, String title, String content) {
    }

    record GoldenQuestion(String id, String question, boolean answerable, List<GoldenTarget> gold) {
    }

    record GoldenTarget(String docId, String keyword) {
    }

    /** 黄金集文件：每行一个 JSON，kind=doc 是语料，kind=question 是评测问题。 */
    record GoldenSet(List<GoldenDocument> documents, List<GoldenQuestion> questions) {

        static GoldenSet load() throws IOException {
            ObjectMapper mapper = new ObjectMapper();
            List<GoldenDocument> documents = new ArrayList<>();
            List<GoldenQuestion> questions = new ArrayList<>();
            try (InputStream input = GoldenSet.class.getResourceAsStream("/eval/rag-golden-set.jsonl")) {
                if (input == null) {
                    throw new IllegalStateException("找不到黄金集 /eval/rag-golden-set.jsonl");
                }
                String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : content.lines().toList()) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = mapper.readTree(line);
                    String kind = node.path("kind").asText();
                    if ("doc".equals(kind)) {
                        documents.add(new GoldenDocument(node.path("docId").asText(), node.path("title").asText(),
                                node.path("content").asText()));
                    } else if ("question".equals(kind)) {
                        List<GoldenTarget> gold = new ArrayList<>();
                        for (JsonNode target : node.path("gold")) {
                            gold.add(new GoldenTarget(target.path("docId").asText(), target.path("keyword").asText()));
                        }
                        questions.add(new GoldenQuestion(node.path("id").asText(), node.path("q").asText(),
                                node.path("answerable").asBoolean(), List.copyOf(gold)));
                    } else {
                        throw new IllegalStateException("黄金集出现未知 kind: " + kind);
                    }
                }
            }
            if (documents.isEmpty() || questions.isEmpty()) {
                throw new IllegalStateException("黄金集为空：docs=" + documents.size() + " questions=" + questions.size());
            }
            return new GoldenSet(List.copyOf(documents), List.copyOf(questions));
        }
    }

    /** 单条问题的判定结果：chunks 是最终进入提示词的切片，ranks 是每条 gold 的命中排名（0 = 未命中）。 */
    record QuestionOutcome(GoldenQuestion question, List<RetrievedChunk> chunks, List<Integer> ranks) {

        static QuestionOutcome of(GoldenQuestion question, RetrievalResult result) {
            List<RetrievedChunk> chunks = result.chunks().stream().map(ContextChunk::chunk).toList();
            List<Integer> ranks = new ArrayList<>(question.gold().size());
            for (GoldenTarget target : question.gold()) {
                ranks.add(rankOf(chunks, target));
            }
            return new QuestionOutcome(question, chunks, List.copyOf(ranks));
        }

        private static int rankOf(List<RetrievedChunk> chunks, GoldenTarget target) {
            int limit = Math.min(RECALL_WINDOW, chunks.size());
            for (int index = 0; index < limit; index++) {
                RetrievedChunk chunk = chunks.get(index);
                boolean sameDocument = target.docId().equals(chunk.docId());
                boolean keywordHit = chunk.text() != null && chunk.text().contains(target.keyword());
                if (sameDocument && keywordHit) {
                    return index + 1;
                }
            }
            return 0;
        }

        boolean matched() {
            return ranks.stream().anyMatch(rank -> rank > 0);
        }
    }

    record EvalReport(int answerable, int unanswerable, int goldTargets, int matchedTargets,
                      int answerableEmpty, double recallAt5, double mrr, double rejectionAccuracy) {

        static EvalReport of(List<QuestionOutcome> outcomes) {
            int answerable = 0;
            int unanswerable = 0;
            int rejected = 0;
            int answerableEmpty = 0;
            int goldTargets = 0;
            int matchedTargets = 0;
            double reciprocalRankSum = 0;
            for (QuestionOutcome outcome : outcomes) {
                if (!outcome.question().answerable()) {
                    unanswerable++;
                    if (outcome.chunks().isEmpty()) {
                        rejected++;
                    }
                    continue;
                }
                answerable++;
                if (outcome.chunks().isEmpty()) {
                    answerableEmpty++;
                }
                goldTargets += outcome.ranks().size();
                matchedTargets += (int) outcome.ranks().stream().filter(rank -> rank > 0).count();
                int bestRank = outcome.ranks().stream().filter(rank -> rank > 0).mapToInt(Integer::intValue)
                        .min().orElse(0);
                reciprocalRankSum += bestRank > 0 ? 1.0 / bestRank : 0;
            }
            double recall = goldTargets == 0 ? 0 : matchedTargets / (double) goldTargets;
            double mrr = answerable == 0 ? 0 : reciprocalRankSum / answerable;
            double rejection = unanswerable == 0 ? 1 : rejected / (double) unanswerable;
            return new EvalReport(answerable, unanswerable, goldTargets, matchedTargets, answerableEmpty,
                    recall, mrr, rejection);
        }

        /** 只列没达标的问题，避免失败信息淹没在 60 条明细里。 */
        String diagnostics(List<QuestionOutcome> outcomes) {
            StringBuilder builder = new StringBuilder("\n");
            for (QuestionOutcome outcome : outcomes) {
                GoldenQuestion question = outcome.question();
                if (question.answerable() && (outcome.chunks().isEmpty() || !outcome.matched())) {
                    builder.append("- ").append(question.id()).append(" 漏召回 ").append(question.question())
                            .append(" | 期望 ").append(describeGold(question))
                            .append(" | 实际 ").append(describeChunks(outcome)).append('\n');
                } else if (!question.answerable() && !outcome.chunks().isEmpty()) {
                    builder.append("- ").append(question.id()).append(" 误召回 ").append(question.question())
                            .append(" | 实际 ").append(describeChunks(outcome)).append('\n');
                }
            }
            return builder.length() <= 1 ? "全部命中" : builder.toString();
        }

        private static String describeGold(GoldenQuestion question) {
            return question.gold().stream()
                    .map(target -> target.docId() + "/" + target.keyword())
                    .reduce((left, right) -> left + " + " + right)
                    .orElse("(无)");
        }

        private static String describeChunks(QuestionOutcome outcome) {
            if (outcome.chunks().isEmpty()) {
                return "(空)";
            }
            StringBuilder builder = new StringBuilder();
            int rank = 1;
            for (RetrievedChunk chunk : outcome.chunks()) {
                if (rank > 1) {
                    builder.append(" , ");
                }
                builder.append('[').append(rank++).append(']').append(chunk.docId())
                        .append('(').append(round(chunk.score())).append(')');
            }
            return builder.toString();
        }
    }
}