package com.aiflow.engine.persistence;

import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.persistence.entity.MessageEntity;
import com.aiflow.engine.persistence.entity.NodeRunEntity;
import com.aiflow.engine.persistence.entity.RunCheckpointEntity;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.NodeRunRepository;
import com.aiflow.engine.persistence.repo.RunCheckpointRepository;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.run.RunCheckpoint;
import com.aiflow.engine.run.RunExecutionGuard;
import com.aiflow.engine.run.RunExecutionRegistry;
import com.aiflow.engine.run.RunLeaseLostException;
import com.aiflow.engine.run.RunRequest;
import com.aiflow.engine.run.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistenceExecutionGuardTest {

    private final UUID runId = UUID.randomUUID();
    private final UUID nodeRunId = UUID.randomUUID();
    private final UUID checkpointId = UUID.randomUUID();
    private final RunExecutionRegistry executions = new RunExecutionRegistry();
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final NodeRunRepository nodes = mock(NodeRunRepository.class);
    private final RunCheckpointRepository checkpoints = mock(RunCheckpointRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final JpaRunRecorder recorder = new JpaRunRecorder(runs, nodes, mapper, executions, messages);
    private final JpaCheckpointStore store = new JpaCheckpointStore(checkpoints, mapper, executions);

    @Test
    void staleOwnerCannotMutateRunNodeOrCheckpoint() {
        NodeRunEntity node = new NodeRunEntity();
        node.setId(nodeRunId);
        node.setRunId(runId);
        when(nodes.findById(nodeRunId)).thenReturn(Optional.of(node));
        when(checkpoints.findById(checkpointId)).thenReturn(Optional.of(new RunCheckpointEntity(checkpointId,
                runId, "node", 1, mapper.createObjectNode(), Instant.now())));
        try (var registration = executions.register(runId, new RunExecutionGuard() {
            @Override
            public void check() {
                throw new RunLeaseLostException("stale");
            }

            @Override
            public <T> T write(Supplier<T> action) {
                throw new RunLeaseLostException("stale");
            }
        })) {
            List<Runnable> writes = List.of(
                    () -> recorder.startRun(request(), definition()),
                    () -> recorder.startNodeRun(runId, nodeDef(), 1, Map.of()),
                    () -> recorder.finishNodeRun(nodeRunId, NodeRunStatus.SUCCEEDED, Map.of(), null, Instant.now()),
                    () -> recorder.suspendNodeRun(nodeRunId, Map.of()),
                    () -> recorder.markWaiting(runId, "node"),
                    () -> recorder.claimResume(runId),
                    () -> recorder.finishRun(runId, RunStatus.SUCCEEDED, Map.of("reply", "stale"), null, 1),
                    () -> store.save(runId, checkpoint()),
                    () -> store.consume(checkpointId));
            writes.forEach(write -> assertThatThrownBy(write::run).isInstanceOf(RunLeaseLostException.class));
        }
        verify(runs, never()).save(any());
        verify(runs, never()).transitionStatus(any(), any(), any());
        verify(nodes, never()).save(any());
        verify(checkpoints, never()).save(any());
        verify(messages, never()).save(any());
    }

    @Test
    void runTerminalStateAndAssistantReplyUseTheSameGuardedWrite() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(runId);
        run.setSessionId(UUID.randomUUID());
        run.setUserId(UUID.randomUUID());
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(messages.findFirstByRunIdAndRole(runId, "ASSISTANT")).thenReturn(Optional.empty());
        AtomicBoolean insideWrite = new AtomicBoolean();
        when(runs.save(any())).thenAnswer(invocation -> {
            assertThat(insideWrite).isTrue();
            return invocation.getArgument(0);
        });
        when(messages.save(any())).thenAnswer(invocation -> {
            assertThat(insideWrite).isTrue();
            MessageEntity reply = invocation.getArgument(0);
            assertThat(reply.getContent()).isEqualTo("answer");
            assertThat(reply.getRunId()).isEqualTo(runId);
            return reply;
        });
        try (var registration = executions.register(runId, new RunExecutionGuard() {
            @Override
            public void check() {
            }

            @Override
            public <T> T write(Supplier<T> action) {
                insideWrite.set(true);
                try {
                    return action.get();
                } finally {
                    insideWrite.set(false);
                }
            }
        })) {
            recorder.finishRun(runId, RunStatus.SUCCEEDED, Map.of("reply", "answer"), null, 1);
        }
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        verify(messages).save(any());
    }

    @Test
    void existingAssistantReplyAndCanceledRunsDoNotCreateDuplicates() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(runId);
        run.setSessionId(UUID.randomUUID());
        run.setUserId(UUID.randomUUID());
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(messages.findFirstByRunIdAndRole(runId, "ASSISTANT")).thenReturn(Optional.of(
                new MessageEntity(UUID.randomUUID(), run.getSessionId(), run.getUserId(), "ASSISTANT", "TEXT",
                        "existing", null, runId, Instant.now())));
        recorder.finishRun(runId, RunStatus.SUCCEEDED, Map.of("reply", "answer"), null, 1);
        recorder.finishRun(runId, RunStatus.CANCELED, Map.of("reply", "partial"), "canceled", 1);
        verify(messages, never()).save(any());
    }

    private RunRequest request() {
        return new RunRequest(runId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of());
    }

    private NodeDef nodeDef() {
        return new NodeDef("node", "test", "node", Map.of(), Set.of(), null, null);
    }

    private WorkflowDefinition definition() {
        return new WorkflowDefinition("wf", "test", 1, List.of(nodeDef()), List.of());
    }

    private RunCheckpoint checkpoint() {
        return new RunCheckpoint("node", nodeRunId, 1, Set.of("node"), Map.of(), 1, 1);
    }
}
