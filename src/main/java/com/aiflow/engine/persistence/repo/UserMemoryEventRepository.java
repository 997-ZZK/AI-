package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.UserMemoryEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 长期记忆的变更流水。写入永远是「追加一行」，删除只有一种：随条目硬删一起清掉。 */
public interface UserMemoryEventRepository extends JpaRepository<UserMemoryEventEntity, UUID> {

    List<UserMemoryEventEntity> findByUserIdOrderByAtDescIdDesc(UUID userId, Pageable pageable);

    long countByUserId(UUID userId);

    long countByMemoryId(UUID memoryId);

    /** 删除语义的一部分：删条目就删它的事件（用户说「删掉」就该删干净，见 V8 的注释）。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from UserMemoryEventEntity e where e.memoryId = :memoryId")
    int deleteByMemoryId(@Param("memoryId") UUID memoryId);
}
