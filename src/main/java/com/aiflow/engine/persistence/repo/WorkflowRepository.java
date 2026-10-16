package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.WorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository extends JpaRepository<WorkflowEntity, UUID> {

    Optional<WorkflowEntity> findFirstByNameOrderByVersionDesc(String name);

    /** demo 工作流的幂等注册按「名字 + 版本」判重：改了 JSON 就升 version，重启即生效。 */
    boolean existsByNameAndVersion(String name, int version);
}
