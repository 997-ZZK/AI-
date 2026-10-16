package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.profile.ProfileStatistics;
import com.aiflow.engine.run.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Scalar projections avoid loading user messages, run input or tool arguments. */
@Repository
public class ProfileStatisticsRepository {

    private final EntityManager entities;

    public ProfileStatisticsRepository(EntityManager entities) {
        this.entities = entities;
    }

    public List<ProfileStatistics.RunSample> runs(UUID userId, Instant from, Instant to) {
        return entities.createQuery("select r.id, r.workflowId, w.name, r.workflowVersion, r.status, r.createdAt "
                        + "from WorkflowRunEntity r left join WorkflowEntity w on w.id = r.workflowId "
                        + "where r.userId = :user and r.createdAt >= :from and r.createdAt <= :to", Object[].class)
                .setParameter("user", userId).setParameter("from", from).setParameter("to", to).getResultList()
                .stream().map(row -> new ProfileStatistics.RunSample((UUID) row[0], (UUID) row[1], (String) row[2],
                        (Integer) row[3], ((RunStatus) row[4]).name(), (Instant) row[5])).toList();
    }

    public List<ProfileStatistics.NodeSample> nodes(UUID userId, Instant from, Instant to) {
        // Use node time, independently of run.created_at: a run can resume after the window floor.
        // JSON is immediately reduced to usage metadata; no reply or prompt leaves this repository.
        return entities.createQuery("select n.nodeType, n.startedAt, n.output from NodeRunEntity n, WorkflowRunEntity r "
                        + "where n.runId = r.id and r.userId = :user "
                        + "and n.nodeType in ('llm.chat', 'llm.vision') "
                        + "and n.startedAt >= :from and n.startedAt <= :to", Object[].class)
                .setParameter("user", userId).setParameter("from", from).setParameter("to", to).getResultList()
                .stream().map(row -> node((String) row[0], (Instant) row[1], (JsonNode) row[2])).toList();
    }

    public List<ProfileStatistics.ToolSample> tools(UUID userId, Instant from, Instant to) {
        return entities.createQuery("select e.toolId, e.kind, e.outcome, count(e) from ToolAuditEventEntity e "
                        + "where e.userId = :user and e.at >= :from and e.at <= :to "
                        + "group by e.toolId, e.kind, e.outcome", Object[].class)
                .setParameter("user", userId).setParameter("from", from).setParameter("to", to).getResultList()
                .stream().map(row -> new ProfileStatistics.ToolSample((String) row[0], (String) row[1],
                        (String) row[2], (Long) row[3])).toList();
    }

    static ProfileStatistics.NodeSample node(String type, Instant at, JsonNode output) {
        JsonNode token = output == null ? null : output.get("tokensUsed");
        Long tokens = token != null && token.isIntegralNumber() && token.canConvertToLong() && token.longValue() >= 0
                ? token.longValue() : null;
        JsonNode provider = output == null ? null : output.get("provider");
        return new ProfileStatistics.NodeSample(type, at, tokens,
                provider != null && provider.isTextual() ? provider.textValue() : null);
    }
}
