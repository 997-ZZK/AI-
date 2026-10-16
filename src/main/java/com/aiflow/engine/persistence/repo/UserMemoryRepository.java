package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.memory.longterm.MemoryKind;
import com.aiflow.engine.persistence.entity.UserMemoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 长期记忆条目的读写。
 *
 * <p>{@code findByUserIdAndKindAndMemKey} 是归并的落点：唯一约束与它一起保证「同一件事只有一行」。
 * 写路径刻意**不做读-改-写**（那是并发下重复写入的经典来源）：调用方先查这一行决定 CREATE / UPDATE，
 * 插入撞唯一约束时按丢弃处理。
 */
public interface UserMemoryRepository extends JpaRepository<UserMemoryEntity, UUID> {

    Optional<UserMemoryEntity> findByUserIdAndKindAndMemKey(UUID userId, MemoryKind kind, String memKey);

    List<UserMemoryEntity> findByUserIdOrderByUpdatedAtDescIdDesc(UUID userId, Pageable pageable);

    List<UserMemoryEntity> findByUserIdAndKindOrderByUpdatedAtDescIdDesc(UUID userId, MemoryKind kind,
                                                                        Pageable pageable);

    List<UserMemoryEntity> findByUserId(UUID userId);

    /** 画像只读取固定槽位；查询包含其他来源/种类以便预览给出忽略原因。 */
    List<UserMemoryEntity> findByUserIdAndMemKeyIn(UUID userId, Collection<String> memKeys);

    long countByUserId(UUID userId);

    /** 被召回条目的观测回写（hits / last_used_at）：一次一条 SQL，失败只 WARN，不拖慢问答。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserMemoryEntity m set m.hits = m.hits + 1, m.lastUsedAt = :now where m.id in :ids")
    int touchHits(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);
}
