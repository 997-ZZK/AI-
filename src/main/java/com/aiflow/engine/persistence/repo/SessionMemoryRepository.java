package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.SessionMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话摘要的读写。写路径全部是**条件更新 / 单条语句**：
 *
 * <ul>
 *   <li>{@link #advance} 带 {@code expected}（乐观锁）：多实例同时折叠时只有一个赢家，输的那个直接放弃；</li>
 *   <li>不在这里做「读-改-写」：那正是重复折叠的经典来源。</li>
 * </ul>
 */
public interface SessionMemoryRepository extends JpaRepository<SessionMemoryEntity, UUID> {

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SessionMemoryEntity m set m.summary = :summary, m.summarizedCount = :upto, "
            + "m.summaryChars = :chars, m.model = :model, m.tokens = m.tokens + :tokens, m.attempts = 0, "
            + "m.lastError = null, m.updatedAt = :now "
            + "where m.sessionId = :sessionId and m.summarizedCount = :expected")
    int advance(@Param("sessionId") UUID sessionId, @Param("expected") int expected, @Param("upto") int upto,
                @Param("summary") String summary, @Param("chars") int chars, @Param("model") String model,
                @Param("tokens") int tokens, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SessionMemoryEntity m set m.attempts = m.attempts + 1, m.lastError = :error, "
            + "m.updatedAt = :now where m.sessionId = :sessionId")
    int recordFailure(@Param("sessionId") UUID sessionId, @Param("error") String error, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SessionMemoryEntity m set m.summary = null, m.summarizedCount = 0, m.summaryChars = 0, "
            + "m.attempts = 0, m.lastError = null, m.updatedAt = :now where m.sessionId = :sessionId")
    int reset(@Param("sessionId") UUID sessionId, @Param("now") Instant now);
}
