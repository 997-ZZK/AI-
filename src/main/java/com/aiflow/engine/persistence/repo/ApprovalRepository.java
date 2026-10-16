package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.approval.ApprovalDecision;
import com.aiflow.engine.approval.ApprovalStatus;
import com.aiflow.engine.persistence.entity.ApprovalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<ApprovalEntity, UUID> {

    List<ApprovalEntity> findByRunIdOrderByCreatedAtAsc(UUID runId);

    Optional<ApprovalEntity> findFirstByRunIdAndNodeIdAndStatus(UUID runId, String nodeId, ApprovalStatus status);

    /** 超时扫描：只取到期的 PENDING，批量有上限（一轮处理不完就下一轮，不把一次扫描拖成长事务）。 */
    List<ApprovalEntity> findTop50ByStatusAndDueAtBeforeOrderByDueAtAsc(ApprovalStatus status, Instant now);

    List<ApprovalEntity> findTop200ByStatusOrderByCreatedAtAsc(ApprovalStatus status);

    /**
     * 收口一条待办（**单赢家**）：只有仍是 PENDING 的那一次能改成功。
     *
     * <p>人工决策、超时扫描、取消三条路都走这里。先查后写在单实例上也够用，一旦多实例就会双双成功 ——
     * 那样会拿到两次「通过」并触发两次恢复，所以条件必须写在 SQL 里。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ApprovalEntity a set a.status = :status, a.decision = :decision, a.decidedBy = :actor, "
            + "a.decidedAt = :at, a.comment = :comment, a.updatedAt = :at "
            + "where a.id = :id and a.status = com.aiflow.engine.approval.ApprovalStatus.PENDING")
    int closeIfPending(@Param("id") UUID id, @Param("status") ApprovalStatus status,
                       @Param("decision") ApprovalDecision decision, @Param("actor") String actor,
                       @Param("comment") String comment, @Param("at") Instant at);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ApprovalEntity a set a.status = :status, a.updatedAt = :at "
            + "where a.runId = :runId and a.status = com.aiflow.engine.approval.ApprovalStatus.PENDING")
    int cancelPendingForRun(@Param("runId") UUID runId, @Param("status") ApprovalStatus status,
                            @Param("at") Instant at);
}
