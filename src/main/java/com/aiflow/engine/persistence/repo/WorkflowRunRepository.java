package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.run.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Optional;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {

    /** Scalar lookup leaves the entity cache empty before the control transaction takes its locks. */
    @Query("select r.sessionId from WorkflowRunEntity r where r.id = :id")
    Optional<UUID> findSessionIdByRunId(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WorkflowRunEntity r where r.id = :id")
    Optional<WorkflowRunEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 状态条件更新（单赢家）。
     *
     * <p>恢复、取消挂起中的运行都靠它抢：并发请求里只有一个能把状态推过去，
     * 「先查后写」在两个实例上会双双成功 —— 这正是多实例下最经典的重复执行来源。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WorkflowRunEntity r set r.status = :to, r.waitingNodeId = null "
            + "where r.id = :id and r.status = :from")
    int transitionStatus(@Param("id") UUID id, @Param("from") RunStatus from, @Param("to") RunStatus to);
}
