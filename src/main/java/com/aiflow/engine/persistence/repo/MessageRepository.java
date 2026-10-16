package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.MessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    Optional<MessageEntity> findFirstBySessionIdAndClientMsgId(UUID sessionId, String clientMsgId);

    Optional<MessageEntity> findFirstByRunIdAndRole(UUID runId, String role);

    /** 最近 N 条（倒序取，调用方翻正）—— 会话窗口用。 */
    List<MessageEntity> findBySessionIdOrderByCreatedAtDescIdDesc(UUID sessionId, Pageable pageable);

    long countBySessionId(UUID sessionId);

    /**
     * 按 {@code (created_at, id)} 全序取第 offset 条起的 limit 条 —— 滚动摘要折叠用。
     *
     * <p>用 {@code OFFSET ... FETCH NEXT}（标准 SQL，PG 与测试用的 H2 都支持）而不是 Pageable：
     * Pageable 的偏移是 {@code page * size}，而这里的偏移是一个**连续增长的计数**，
     * 硬凑页号会在 chunk-size 调整后算错位置（摘要折叠最怕的就是折错区间）。
     */
    @Query(value = "select * from messages where session_id = :sessionId "
            + "order by created_at asc, id asc offset :offset rows fetch next :limit rows only", nativeQuery = true)
    List<MessageEntity> fetchSlice(@Param("sessionId") UUID sessionId, @Param("offset") int offset,
                                   @Param("limit") int limit);
}
