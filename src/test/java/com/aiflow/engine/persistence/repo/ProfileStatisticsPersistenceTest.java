package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.persistence.entity.NodeRunEntity;
import com.aiflow.engine.persistence.entity.ToolAuditEventEntity;
import com.aiflow.engine.persistence.entity.UserEntity;
import com.aiflow.engine.persistence.entity.WorkflowEntity;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.run.RunStatus;
import com.aiflow.engine.tool.ToolKind;
import com.aiflow.engine.tool.audit.ToolAuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the real JPA projections, especially resumed nodes of old runs. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProfileStatisticsPersistenceTest {

    @Autowired ProfileStatisticsRepository statistics;
    @Autowired UserRepository users;
    @Autowired WorkflowRepository workflows;
    @Autowired WorkflowRunRepository runs;
    @Autowired NodeRunRepository nodes;
    @Autowired ToolAuditEventRepository audits;
    @Autowired ObjectMapper mapper;

    @Test
    void resumedNodesUseTheirOwnWindowAndCannotLeakAnotherUsersUsage() throws Exception {
        Instant now = Instant.parse("2026-10-09T06:00:00Z");
        Instant from = now.minus(Duration.ofDays(30));
        UUID user = user(now), other = user(now);
        UUID workflow = UUID.randomUUID();
        workflows.saveAndFlush(new WorkflowEntity(workflow, "profile-persistence-" + UUID.randomUUID(), 3,
                mapper.readTree("{}"), now));

        WorkflowRunEntity oldRun = run(user, workflow, from.minusSeconds(1), RunStatus.SUCCEEDED);
        // Approval recovery may overwrite started_at but must not pull the run back into this window.
        oldRun.setStartedAt(now.minusSeconds(10));
        runs.saveAndFlush(oldRun);
        WorkflowRunEntity current = run(user, workflow, from, RunStatus.WAITING_APPROVAL);
        WorkflowRunEntity another = run(other, workflow, now.minusSeconds(10), RunStatus.SUCCEEDED);

        node(oldRun.getId(), now.minusSeconds(10), "llm.chat", "{\"tokensUsed\":12,\"provider\":\"mock\"}");
        node(oldRun.getId(), from.minusSeconds(1), "llm.chat", "{\"tokensUsed\":1000,\"provider\":\"mock\"}");
        node(current.getId(), now.minusSeconds(5), "llm.vision", "{\"tokensUsed\":1.5,\"provider\":\"qwen\"}");
        node(current.getId(), now.minusSeconds(5), "memory.summary", "{\"tokensUsed\":900,\"provider\":\"qwen\"}");
        node(another.getId(), now.minusSeconds(5), "llm.chat", "{\"tokensUsed\":999,\"provider\":\"qwen\"}");
        node(oldRun.getId(), now.plusSeconds(1), "llm.chat", "{\"tokensUsed\":2000,\"provider\":\"mock\"}");

        assertThat(statistics.runs(user, from, now)).hasSize(1)
                .allSatisfy(sample -> {
                    assertThat(sample.runId()).isEqualTo(current.getId());
                    assertThat(sample.status()).isEqualTo("WAITING_APPROVAL");
                    assertThat(sample.name()).startsWith("profile-persistence-");
                    assertThat(sample.version()).isEqualTo(3);
                });
        assertThat(statistics.nodes(user, from, now)).hasSize(2)
                .anySatisfy(sample -> assertThat(sample.tokens()).isEqualTo(12L))
                .anySatisfy(sample -> assertThat(sample.tokens()).isNull());
    }

    @Test
    void toolGroupsPreserveReplayAndFilterUserAndEventTime() {
        Instant now = Instant.parse("2026-10-09T06:00:00Z");
        Instant from = now.minus(Duration.ofDays(30));
        UUID user = user(now), other = user(now);
        audit(user, from, ToolAuditEvent.SUCCESS);
        audit(user, now, ToolAuditEvent.SUCCESS);
        audit(user, now, ToolAuditEvent.REPLAYED);
        audit(user, now, ToolAuditEvent.DENIED);
        audit(user, now, ToolAuditEvent.FAILED);
        audit(user, from.minusSeconds(1), ToolAuditEvent.SUCCESS);
        audit(user, now.plusSeconds(1), ToolAuditEvent.SUCCESS);
        audit(other, now, ToolAuditEvent.SUCCESS);
        var groups = statistics.tools(user, from, now);
        assertThat(groups).hasSize(4)
                .anySatisfy(group -> {
                    assertThat(group.outcome()).isEqualTo("SUCCESS");
                    assertThat(group.count()).isEqualTo(2);
                });
        assertThat(groups.stream().mapToLong(sample -> sample.count()).sum()).isEqualTo(5);
    }

    private UUID user(Instant now) {
        UUID id = UUID.randomUUID();
        users.saveAndFlush(new UserEntity(id, "profile-repo-" + UUID.randomUUID(), null, now));
        return id;
    }

    private WorkflowRunEntity run(UUID user, UUID workflow, Instant at, RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(UUID.randomUUID());
        run.setUserId(user);
        run.setWorkflowId(workflow);
        run.setWorkflowVersion(3);
        run.setStatus(status);
        run.setCreatedAt(at);
        run.setStartedAt(at);
        return runs.saveAndFlush(run);
    }

    private void node(UUID run, Instant at, String type, String output) throws Exception {
        NodeRunEntity node = new NodeRunEntity();
        node.setId(UUID.randomUUID());
        node.setRunId(run);
        node.setNodeId("profile-node-" + UUID.randomUUID());
        node.setNodeType(type);
        node.setStatus(NodeRunStatus.SUCCEEDED);
        node.setAttempt(1);
        node.setStartedAt(at);
        node.setEndedAt(at);
        node.setOutput(mapper.readTree(output));
        nodes.saveAndFlush(node);
    }

    private void audit(UUID user, Instant at, String outcome) {
        audits.saveAndFlush(ToolAuditEventEntity.of(new ToolAuditEvent(at, outcome, user, null, null,
                "skill.read", ToolKind.SKILL, null, 0, null), at));
    }
}
