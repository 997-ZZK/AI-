package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.RunCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RunCheckpointRepository extends JpaRepository<RunCheckpointEntity, UUID> {

    Optional<RunCheckpointEntity> findFirstByRunIdAndConsumedAtIsNullOrderBySeqDesc(UUID runId);

    @Query("select coalesce(max(c.seq), 0) from RunCheckpointEntity c where c.runId = :runId")
    int maxSeq(@Param("runId") UUID runId);
}
