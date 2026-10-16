package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.DispatchTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Queries only; transitions belong to the lease-aware store. */
public interface DispatchTaskRepository extends JpaRepository<DispatchTaskEntity, UUID> {
    List<DispatchTaskEntity> findByRunIdOrderBySeqAsc(UUID runId);
    List<DispatchTaskEntity> findBySessionIdOrderBySeqAsc(UUID sessionId);
}
