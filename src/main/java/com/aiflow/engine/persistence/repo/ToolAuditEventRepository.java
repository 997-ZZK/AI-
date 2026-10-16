package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.ToolAuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * 审计表访问。
 *
 * <p>查询走 {@link JpaSpecificationExecutor}（只拼非空条件），而不是
 * `where (:userId is null or e.userId = :userId)` 那种 JPQL：后者在 PostgreSQL 上会因为
 * `? is null` 推断不出参数类型而报错，H2 却跑得好好的 —— 典型的「测试绿、生产红」陷阱。
 * Specification 只在条件非空时才生成谓词，没有这个问题。
 */
public interface ToolAuditEventRepository extends JpaRepository<ToolAuditEventEntity, Long>,
        JpaSpecificationExecutor<ToolAuditEventEntity> {

    /** 保留期清理（批量删除而不是逐行删：审计表清理动辄上万行）。 */
    @Modifying
    @Query("delete from ToolAuditEventEntity e where e.at < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
