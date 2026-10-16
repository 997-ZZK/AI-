package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.NodeRunEntity;
import com.aiflow.engine.node.NodeRunStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NodeRunRepository extends JpaRepository<NodeRunEntity, UUID> {

    /** 按开始时间排：节点行的先后就是执行顺序（恢复时断点行仍排在原位）。 */
    List<NodeRunEntity> findByRunIdOrderByStartedAtAsc(UUID runId);

    /** Explicit columns keep input/output/error out of the list query and persistence context. */
    @Query("""
            select n.id as nodeRunId, n.nodeId as nodeId, n.nodeType as nodeType,
                   n.attempt as attempt, n.status as status, n.startedAt as startedAt, n.endedAt as endedAt
            from NodeRunEntity n where n.runId = :runId
            order by case when n.startedAt is null then 1 else 0 end, n.startedAt asc, n.id asc
            """)
    Slice<ReplaySummary> findReplaySummariesByRunId(@Param("runId") UUID runId, Pageable pageable);

    Optional<NodeRunEntity> findByIdAndRunId(UUID id, UUID runId);

    interface ReplaySummary {
        UUID getNodeRunId();
        String getNodeId();
        String getNodeType();
        int getAttempt();
        NodeRunStatus getStatus();
        Instant getStartedAt();
        Instant getEndedAt();
    }
}
