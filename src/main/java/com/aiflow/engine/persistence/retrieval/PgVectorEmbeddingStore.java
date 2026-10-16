package com.aiflow.engine.persistence.retrieval;

import com.aiflow.engine.retrieval.EmbeddingStore;
import com.aiflow.engine.retrieval.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * pgvector 持久化实现：向量写 document_chunks.embedding 列，并支持 ANN 近邻查询。
 *
 * <p>两条查询路径并存：{@link #load} 是精确路径的地基（把整库向量交给 Java 算余弦），
 * {@link #searchSimilar} 是 ANN 路径（`order by embedding <=> ?` 走 HNSW 索引，只取前 limit 条）。
 * 阈值、断崖 top-k、MMR 这些后处理只认分数，两条路径共用同一份 —— 换路径不改语义，只改候选集怎么来。
 */
public class PgVectorEmbeddingStore implements EmbeddingStore {

    /** 向量与模型标记必须在同一条 SQL 里写：分开写会出现「标了模型 A，向量还是模型 B」的中间态。 */
    private static final String UPSERT_SQL =
            "update document_chunks set embedding = cast(? as vector), embedding_model = ? "
                    + "where id = cast(? as uuid)";
    private static final String LOAD_SQL =
            "select id::text as chunk_id, embedding::text as embedding from document_chunks "
                    + "where kb_id = ? and embedding_model = ? and embedding is not null";
    /** 该 kb 下各 provider 的向量条数：upgrade 后旧数据为 (unknown)，它们不参与检索。 */
    private static final String MODELS_SQL =
            "select coalesce(embedding_model, '(unknown)') as model, count(*) as chunk_count "
                    + "from document_chunks where kb_id = ? and embedding is not null group by 1";

    /** ANN 查询：余弦相似度 = 1 - 余弦距离，与精确路径同口径（[-1,1]）。 */
    private static final String ANN_SQL =
            "select id::text as chunk_id, embedding::text as embedding, "
                    + "1 - (embedding <=> cast(? as vector)) as cosine "
                    + "from document_chunks "
                    + "where kb_id = ? and embedding_model = ? and embedding is not null "
                    + "order by embedding <=> cast(? as vector) limit ?";

    /** HNSW 查询期参数只认这几个取值，非法值在装配期就失败，别等到查询时才报语法错误。 */
    private static final Set<String> ITERATIVE_SCAN_MODES = Set.of("strict_order", "relaxed_order", "off");

    private static final Logger log = LoggerFactory.getLogger(PgVectorEmbeddingStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final int dimensions;
    private final int efSearch;
    private final String iterativeScan;
    /** 调优参数把查询带崩过一次就不再尝试（正确性不依赖可选开关）。 */
    private volatile boolean tuningEnabled = true;
    /** 扩展版本只查一次；null = 还没查过，"" = 查过但没装 vector 扩展。 */
    private volatile String pgvectorVersion;
    /** 该版本能用哪些查询期参数：只算一次。 */
    private volatile AnnTuning annTuning;

    public PgVectorEmbeddingStore(JdbcTemplate jdbcTemplate, int dimensions) {
        this(jdbcTemplate, dimensions, 100, "");
    }

    public PgVectorEmbeddingStore(JdbcTemplate jdbcTemplate, int dimensions, int efSearch, String iterativeScan) {
        this.jdbcTemplate = jdbcTemplate;
        this.dimensions = dimensions;
        this.efSearch = Math.max(1, efSearch);
        String scan = iterativeScan == null ? "" : iterativeScan.strip().toLowerCase();
        if (!scan.isEmpty() && !ITERATIVE_SCAN_MODES.contains(scan)) {
            throw new IllegalArgumentException("aiflow.retrieval.ann.iterative-scan 只支持 strict_order / "
                    + "relaxed_order / off（或留空），当前值: " + iterativeScan);
        }
        this.iterativeScan = scan;
    }

    @Override
    public String id() {
        return "pgvector";
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public void upsertAll(String kbId, String embeddingModel, Map<String, float[]> embeddings) {
        if (embeddings.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(embeddings.size());
        for (Map.Entry<String, float[]> entry : embeddings.entrySet()) {
            Vectors.requireDimensions(entry.getValue(), dimensions, "pgvector 写入 " + entry.getKey());
            batch.add(new Object[]{Vectors.toLiteral(entry.getValue()), embeddingModel, entry.getKey()});
        }
        jdbcTemplate.batchUpdate(UPSERT_SQL, batch);
    }

    @Override
    public Map<String, float[]> load(String kbId, String embeddingModel) {
        return jdbcTemplate.query(LOAD_SQL, resultSet -> {
            Map<String, float[]> vectors = new LinkedHashMap<>();
            while (resultSet.next()) {
                float[] vector = Vectors.parse(resultSet.getString("embedding"));
                if (vector != null) {
                    vectors.put(resultSet.getString("chunk_id"), vector);
                }
            }
            return vectors;
        }, kbId, embeddingModel);
    }

    @Override
    public Map<String, Long> indexedModels(String kbId) {
        return jdbcTemplate.query(MODELS_SQL, resultSet -> {
            Map<String, Long> models = new LinkedHashMap<>();
            while (resultSet.next()) {
                models.put(resultSet.getString("model"), resultSet.getLong("chunk_count"));
            }
            return models;
        }, kbId);
    }

    @Override
    public boolean supportsAnn() {
        return true;
    }

    /**
     * ANN 近邻查询。
     *
     * <p>为什么要显式开关自动提交：HNSW 的查询期参数必须与查询在同一事务里生效，所以用 `set local`；
     * 而会话级 `set` 会留在连接池的连接上，把参数泄漏给后续别的查询。
     */
    @Override
    public List<AnnHit> searchSimilar(String kbId, String embeddingModel, float[] query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String literal = Vectors.toLiteral(query);
        try {
            return runAnnQuery(literal, kbId, embeddingModel, limit);
        } catch (DataAccessException e) {
            if (!tuningEnabled) {
                throw e;
            }
            // 调优参数把整条查询带崩了：关掉它再试一次 —— 检索的正确性不该依赖一个可选的性能开关
            log.warn("HNSW 查询期参数设置失败，改为不带调优参数重试: {}", e.getMessage());
            tuningEnabled = false;
            return runAnnQuery(literal, kbId, embeddingModel, limit);
        }
    }

    private List<AnnHit> runAnnQuery(String literal, String kbId, String embeddingModel, int limit) {
        return jdbcTemplate.execute((ConnectionCallback<List<AnnHit>>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                applyAnnTuning(connection);
                try (PreparedStatement statement = connection.prepareStatement(ANN_SQL)) {
                    statement.setString(1, literal);
                    statement.setString(2, kbId);
                    statement.setString(3, embeddingModel);
                    statement.setString(4, literal);
                    statement.setInt(5, limit);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<AnnHit> hits = new ArrayList<>();
                        while (resultSet.next()) {
                            hits.add(new AnnHit(resultSet.getString("chunk_id"), resultSet.getDouble("cosine"),
                                    Vectors.parse(resultSet.getString("embedding"))));
                        }
                        return hits;
                    }
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    private void applyAnnTuning(Connection connection) throws SQLException {
        if (!tuningEnabled) {
            return;
        }
        AnnTuning tuning = annTuning();
        if (!tuning.any()) {
            return;
        }
        // set local 必须与查询同事务：会话级 set 会把参数留在连接池的连接上，泄漏给别人的查询。
        // 另注：本会话若还没用过 HNSW 索引，hnsw.* 还是「占位符 GUC」，set local 依然会被接受，
        // 等扩展 .so 随本次查询加载时按占位符的值生效（已实测确认），所以这里不需要预热。
        try (Statement statement = connection.createStatement()) {
            if (tuning.efSearch()) {
                statement.execute("set local hnsw.ef_search = " + efSearch);
            }
            if (tuning.iterativeScan() && !iterativeScan.isEmpty()) {
                statement.execute("set local hnsw.iterative_scan = " + iterativeScan);
            }
        }
    }

    /**
     * 该 pgvector 版本支持哪些查询期参数，只判定一次。
     *
     * <p>判定依据是 <b>扩展版本</b>而不是 {@code show hnsw.ef_search}：hnsw.* 是扩展 .so 注册的自定义 GUC，
     * 在本会话还没用过 HNSW 索引之前 {@code show} 会报 unrecognized configuration parameter ——
     * 拿它当探活会得出「不支持」的错误结论，把配置好的参数静默吞掉（实测踩过）。
     * {@code _PG_init} 的占位符转换会继承 set local 的值，所以先 set 后查是安全的。
     */
    private AnnTuning annTuning() {
        AnnTuning cached = annTuning;
        if (cached != null) {
            return cached;
        }
        String version = pgvectorVersion();
        AnnTuning discovered = version == null || version.isEmpty()
                ? AnnTuning.NONE
                : new AnnTuning(atLeast(version, 0, 5), atLeast(version, 0, 8));
        annTuning = discovered;
        if (version != null && !version.isEmpty()
                && (!discovered.efSearch() || (!iterativeScan.isEmpty() && !discovered.iterativeScan()))) {
            log.info("pgvector {} 不支持部分 HNSW 查询期参数（ef-search={}, iterative-scan={}）；"
                            + "这些参数按扩展默认值执行，升级 pgvector 后自动生效",
                    version, discovered.efSearch(), discovered.iterativeScan());
        }
        return discovered;
    }

    /** vector 扩展版本；没装扩展（或查不到）返回空串，此时 ANN 查询本身就会失败，不必再设参数。 */
    private String pgvectorVersion() {
        String cached = pgvectorVersion;
        if (cached != null) {
            return cached;
        }
        String version;
        try {
            version = jdbcTemplate.queryForObject(
                    "select extversion from pg_extension where extname = 'vector'", String.class);
        } catch (DataAccessException e) {
            log.warn("读取 pgvector 扩展版本失败，ANN 查询期参数不生效: {}", e.getMessage());
            version = null;
        }
        String resolved = version == null ? "" : version.strip();
        pgvectorVersion = resolved;
        return resolved;
    }

    /** 版本比较只看 major.minor：0.8.1 与 0.8.6 在 GUC 支持上没有差别。 */
    private static boolean atLeast(String version, int major, int minor) {
        String[] parts = version.split("\\.");
        int actualMajor = parsePart(parts, 0);
        int actualMinor = parsePart(parts, 1);
        return actualMajor > major || (actualMajor == major && actualMinor >= minor);
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 该版本能用哪些查询期参数。 */
    private record AnnTuning(boolean efSearch, boolean iterativeScan) {

        private static final AnnTuning NONE = new AnnTuning(false, false);

        private boolean any() {
            return efSearch || iterativeScan;
        }
    }

    @Override
    public void removeAll(Collection<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return;
        }
        String placeholders = chunkIds.stream().map(ignored -> "cast(? as uuid)").collect(Collectors.joining(","));
        jdbcTemplate.update("update document_chunks set embedding = null where id in (" + placeholders + ")",
                chunkIds.toArray());
    }
}
