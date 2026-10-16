package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;

/**
 * 检索链路参数。全部有默认值，离线（mock embedding + memory store）也能跑通。
 */
@ConfigurationProperties(prefix = "aiflow.retrieval")
public record RetrievalProperties(
        @DefaultValue("0.35") double minScore,
        @DefaultValue("6") int topK,
        @DefaultValue("1") int minK,
        @DefaultValue("8") int maxK,
        @DefaultValue("0.35") double cliffRatio,
        @DefaultValue("0.7") double mmrLambda,
        @DefaultValue("true") boolean mergeAdjacent,
        @DefaultValue({"local"}) String[] sources,
        @DefaultValue("pgvector") String embeddingStore,
        @DefaultValue Embedding embedding,
        @DefaultValue Cloud cloud,
        @DefaultValue Ann ann
) {

    /**
     * 向量化配置。
     *
     * <p>{@code min-score} 放在这里而不是检索层：阈值是**向量分布**的属性。mock 是确定性哈希、只对词面
     * 重叠敏感，余弦天然偏低；真实模型对同义改写也给高分，余弦整体上移。同一个数字在两边代表完全不同的
     * 严格程度，所以它必须跟着向量模型走，切换模型时不可能忘掉（同一个配置块）。{@code < 0} = 继承
     * {@code aiflow.retrieval.min-score}。校准记录见 docs/quality-plan.md。
     *
     * <p>{@code mode= mock | http}。{@code http} 时走 {@code tiers}：按顺序尝试，前档失败落下一档，
     * 本地 Ollama 与云端网关吃同一份 OpenAI 兼容契约（只有 base-url / model / api-key / timeout 不同）。
     * 所有档位必须是**同一个模型**：向量只在同一模型的向量空间里可比。
     *
     * <pre>
     * aiflow.retrieval.embedding:
     *   mode: http
     *   model: bge-m3
     *   tiers:
     *     - id: cloud        # 写在前面 = 优先云端加速
     *       base-url: https://&lt;gateway&gt;/v1
     *       api-key: ${AIFLOW_EMBEDDING_API_KEY:}
     *       enabled: false   # 没有密钥就关掉，链条自动只剩本地档
     *     - id: local        # 本地兜底（离线可用）
     *       base-url: http://localhost:11434/v1
     *       timeout-ms: 60000
     * </pre>
     */
    public record Embedding(
            @DefaultValue("mock") String mode,
            @DefaultValue("-1") double minScore,
            @DefaultValue("1024") int dimensions,
            @DefaultValue("bge-m3") String model,
            @DefaultValue("http://localhost:11434/v1") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("30000") long timeoutMs,
            List<EmbeddingTier> tiers
    ) {

        public Embedding {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
        }
    }

    /** embedding 链条上的一档。{@code model} / {@code timeoutMs} 留空即继承上层配置。 */
    public record EmbeddingTier(
            @DefaultValue("") String id,
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String model,
            @DefaultValue("") String apiKey,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0") long timeoutMs
    ) {
    }

    public record Cloud(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("mock") String mode,
            @DefaultValue("cloud") String source,
            @DefaultValue("0") double scoreMin,
            @DefaultValue("100") double scoreMax,
            @DefaultValue("http://localhost:8000/v1") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("default") String datasetId,
            @DefaultValue("4000") long timeoutMs,
            @DefaultValue CloudHttp http
    ) {
        @ConstructorBinding
        public Cloud {
            http = http == null ? CloudHttp.defaults() : http;
        }

        public Cloud(boolean enabled, String mode, String source, double scoreMin, double scoreMax,
                     String baseUrl, String apiKey, String datasetId, long timeoutMs) {
            this(enabled, mode, source, scoreMin, scoreMax, baseUrl, apiKey, datasetId, timeoutMs,
                    CloudHttp.defaults());
        }

        @Override
        public String toString() {
            return "Cloud[enabled=" + enabled + ", mode=" + mode + ", source=" + source
                    + ", scoreMin=" + scoreMin + ", scoreMax=" + scoreMax
                    + ", apiKey=[redacted], timeoutMs=" + timeoutMs + "]";
        }
    }

    /** HTTP JSON 协议边界。仅保存凭证环境变量名，读取发生在实际调用时。 */
    public record CloudHttp(
            @DefaultValue("/retrieve") String retrievePath,
            @DefaultValue("") String apiKeyEnv,
            @DefaultValue("Authorization") String authHeader,
            @DefaultValue("Bearer") String authScheme,
            @DefaultValue("4194304") int maxResponseBytes,
            @DefaultValue("1") int maxAttempts,
            @DefaultValue CloudMapping mapping,
            @DefaultValue("1048576") int maxRequestBytes,
            @DefaultValue("4") int maxConcurrentCalls,
            @DefaultValue("false") boolean retrySafe
    ) {
        @ConstructorBinding
        public CloudHttp {
            mapping = mapping == null ? CloudMapping.defaults() : mapping;
        }

        public CloudHttp(String retrievePath, String apiKeyEnv, String authHeader, String authScheme,
                         int maxResponseBytes, int maxAttempts, CloudMapping mapping) {
            this(retrievePath, apiKeyEnv, authHeader, authScheme, maxResponseBytes, maxAttempts, mapping,
                    1024 * 1024, 4, false);
        }

        public static CloudHttp defaults() {
            return new CloudHttp("/retrieve", "", "Authorization", "Bearer", 4 * 1024 * 1024, 1,
                    CloudMapping.defaults());
        }
    }

    /** 请求键名与响应 JSON Pointer；复杂厂商协议可另实现 KnowledgeBaseProtocol。 */
    public record CloudMapping(
            @DefaultValue("dataset_id") String datasetField,
            @DefaultValue("query") String queryField,
            @DefaultValue("top_k") String topKField,
            @DefaultValue("/chunks") String chunksPointer,
            @DefaultValue("/id") String chunkIdPointer,
            @DefaultValue("/document_id") String documentIdPointer,
            @DefaultValue("/title") String titlePointer,
            @DefaultValue("/content") String contentPointer,
            @DefaultValue("/score") String scorePointer,
            @DefaultValue("/chunk_index") String chunkIndexPointer,
            @DefaultValue("/section") String sectionPointer,
            @DefaultValue("/page_from") String pageFromPointer,
            @DefaultValue("/page_to") String pageToPointer,
            @DefaultValue("/content_hash") String contentHashPointer
    ) {
        public static CloudMapping defaults() {
            return new CloudMapping("dataset_id", "query", "top_k", "/chunks", "/id", "/document_id",
                    "/title", "/content", "/score", "/chunk_index", "/section", "/page_from",
                    "/page_to", "/content_hash");
        }
    }

    /**
     * ANN（pgvector HNSW）查询路径的参数。
     *
     * <p>为什么要它：精确路径把该 kb 的**全部**向量读进 JVM 再逐条算余弦，kb 一大就是「每次检索搬几十 MB」。
     * ANN 把候选集压到 {@code candidates} 条（走 HNSW 索引），阈值 / 断崖 / MMR 这些后处理一行都不用改 ——
     * 它们只认分数，不关心分数是算出来的还是查出来的。
     *
     * <p>{@code candidates} 是「召回宽度」与「单次开销」的折中：太小会漏掉真命中的切片（ANN 只保证近似），
     * 太大就等于退回精确路径。它必须 ≥ aiflow.retrieval.maxK，否则断崖 top-k 没有足够的候选可挑。
     */
    public record Ann(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("48") int candidates,
            @DefaultValue("100") int efSearch,
            /** pgvector 0.8 起支持；带过滤条件的 ANN 查询靠它继续往下扫，否则容易「明明有却查不到」。留空则不设置。 */
            @DefaultValue("relaxed_order") String iterativeScan
    ) {
    }

    public static RetrievalProperties defaults() {
        return new RetrievalProperties(0.35, 6, 1, 8, 0.35, 0.7, true, new String[]{"local"}, "pgvector",
                new Embedding("mock", -1, 1024, "bge-m3", "http://localhost:11434/v1", "", 30_000, List.of()),
                new Cloud(false, "mock", "cloud", 0, 100, "http://localhost:8000/v1", "", "default", 4_000),
                new Ann(true, 48, 100, "relaxed_order"));
    }
}
