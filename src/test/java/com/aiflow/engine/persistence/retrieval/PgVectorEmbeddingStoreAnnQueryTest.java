package com.aiflow.engine.persistence.retrieval;

import com.aiflow.engine.retrieval.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ANN 查询的 SQL 与 HNSW 查询期参数：不连数据库，用假 Connection 把发出的语句抓下来看。
 *
 * <p>要钉死的四件事：
 * <ul>
 *   <li>走的是 pgvector 的距离算子（order by embedding &lt;=&gt; ?），不是把全量向量读进 JVM —— 否则「ANN 优化」名存实亡；</li>
 *   <li>调优参数用 set local 且随事务回滚，不泄漏到连接池里下一条查询；</li>
 *   <li>版本判定只认扩展版本：hnsw.* 是扩展 .so 注册的自定义 GUC，模块没加载时 show 会报 unrecognized，
 *       拿 show 探活会误判成「不支持」并把参数静默吞掉（实测踩过这个坑）；</li>
 *   <li>老版本 pgvector 不认这些 GUC、或设置直接失败时，查询必须照常返回结果：正确性不许挂在可选性能开关上。</li>
 * </ul>
 */
class PgVectorEmbeddingStoreAnnQueryTest {

    private static final String KB = "kb-ann";
    private static final String MODEL = "fake-embedding";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final Connection connection = mock(Connection.class);
    private final Statement tuningStatement = mock(Statement.class);
    private final PreparedStatement preparedStatement = mock(PreparedStatement.class);
    private final ResultSet resultSet = mock(ResultSet.class);
    private final List<String> executed = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void wireFakeJdbc() throws SQLException {
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback callback = invocation.getArgument(0);
            try {
                // 真 JdbcTemplate 会把 SQLException 翻成 DataAccessException，重试逻辑正是靠这个类型分叉的
                return callback.doInConnection(connection);
            } catch (SQLException e) {
                throw new UncategorizedSQLException("ann query", "ann query", e);
            }
        });
        // 默认按装了 pgvector 0.8.6（本机实测版本）走；旧版本/没装扩展的用例各自覆盖
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("0.8.6");
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(tuningStatement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        doAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            return false;
        }).when(tuningStatement).execute(anyString());
    }

    @Test
    void annQueryUsesPgvectorDistanceOperatorAndAppliesHnswTuning() throws SQLException {
        stubOneRow("chunk-1", 0.87);

        List<EmbeddingStore.AnnHit> hits = store(100, "relaxed_order")
                .searchSimilar(KB, MODEL, new float[]{1f, 0f}, 48);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunkId()).isEqualTo("chunk-1");
        assertThat(hits.get(0).cosine()).isEqualTo(0.87);
        assertThat(hits.get(0).embedding()).containsExactly(0f, 1f);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("embedding <=> cast(? as vector)").contains("order by embedding <=>");

        assertThat(executed).contains("set local hnsw.ef_search = 100");
        assertThat(executed).contains("set local hnsw.iterative_scan = relaxed_order");
        // set local 只在一个短事务里活着：不回滚就会把参数泄漏给连接池里的下一条查询
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void blankIterativeScanLeavesTheGucUntouched() throws SQLException {
        stubOneRow("chunk-1", 0.5);

        store(64, "").searchSimilar(KB, MODEL, new float[]{1f, 0f}, 8);

        assertThat(executed).contains("set local hnsw.ef_search = 64");
        assertThat(executed).noneMatch(sql -> sql.contains("iterative_scan"));
    }

    @Test
    void oldPgvectorWithoutIterativeScanOnlySetsEfSearch() throws SQLException {
        stubOneRow("chunk-1", 0.42);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("0.7.4");

        List<EmbeddingStore.AnnHit> hits = store(100, "relaxed_order")
                .searchSimilar(KB, MODEL, new float[]{1f, 0f}, 48);

        assertThat(hits).hasSize(1);
        assertThat(executed).containsExactly("set local hnsw.ef_search = 100");
    }

    @Test
    void missingVectorExtensionSkipsTuningAndStillRunsTheQuery() throws SQLException {
        stubOneRow("chunk-1", 0.42);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        List<EmbeddingStore.AnnHit> hits = store(100, "relaxed_order")
                .searchSimilar(KB, MODEL, new float[]{1f, 0f}, 48);

        assertThat(hits).hasSize(1);
        assertThat(executed).isEmpty();
    }

    @Test
    void failingTuningIsRetriedWithoutItInsteadOfFailingTheQuery() throws SQLException {
        stubOneRow("chunk-1", 0.33);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            executed.add(sql);
            if (sql.startsWith("set local")) {
                throw new SQLException("permission denied to set parameter");
            }
            return false;
        }).when(tuningStatement).execute(anyString());

        List<EmbeddingStore.AnnHit> hits = store(100, "relaxed_order")
                .searchSimilar(KB, MODEL, new float[]{1f, 0f}, 48);

        assertThat(hits).hasSize(1);
        assertThat(executed.stream().filter(sql -> sql.equals("set local hnsw.ef_search = 100"))).hasSize(1);
    }

    @Test
    void unknownIterativeScanModeFailsAtStartupNotAtQueryTime() {
        assertThatThrownBy(() -> new PgVectorEmbeddingStore(jdbcTemplate, 1024, 100, "strict"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iterative-scan");
    }

    @Test
    void documentedIterativeScanModesAreAcceptedAndBlankIsAllowed() {
        for (String mode : List.of("strict_order", "relaxed_order", "off", "", "relaxed_order ")) {
            PgVectorEmbeddingStore store = new PgVectorEmbeddingStore(jdbcTemplate, 1024, 100, mode);
            assertThat(store.supportsAnn()).as("mode=%s", mode).isTrue();
        }
    }

    private PgVectorEmbeddingStore store(int efSearch, String iterativeScan) {
        return new PgVectorEmbeddingStore(jdbcTemplate, 1024, efSearch, iterativeScan);
    }

    private void stubOneRow(String chunkId, double cosine) throws SQLException {
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("chunk_id")).thenReturn(chunkId);
        when(resultSet.getString("embedding")).thenReturn("[0.000000,1.000000]");
        when(resultSet.getDouble("cosine")).thenReturn(cosine);
    }
}
