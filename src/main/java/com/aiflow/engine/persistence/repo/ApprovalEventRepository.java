package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.ApprovalEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovalEventRepository extends JpaRepository<ApprovalEventEntity, Long> {

    List<ApprovalEventEntity> findByApprovalIdOrderByCreatedAtAsc(UUID approvalId);
}
