package com.aiflow.engine.run;

import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.event.EventPublishException;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.graph.GraphValidator;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.SpelConditionEvaluator;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeRegistry;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionEngineEventFailureTest {
    @ParameterizedTest
    @EnumSource(value = RunEventType.class, names = {"RUN_STARTED", "NODE_STARTED", "DELTA", "RUN_SUCCEEDED"})
    void eventTransportFailureStillClosesNodeAndRunAsSucceeded(RunEventType failedType) {
        List<RunEventType> attempted = new ArrayList<>();
        RunEventBus events = new RunEventBus() {
            public RunEvent publish(RunEvent event) {
                attempted.add(event.type());
                if (event.type() == failedType) {
                    throw new EventPublishException(new IllegalStateException("synthetic transport failure"));
                }
                return event;
            }
            public Subscription subscribe(String runId, long cursor, Consumer<RunEvent> consumer) {
                return () -> {};
            }
        };
        NodeExecutor node = new NodeExecutor() {
            public String type() { return "test.event"; }
            public NodeResult execute(NodeContext context) {
                context.emitDelta("synthetic response");
                return NodeResult.success(Map.of("reply", "synthetic response"));
            }
        };
        NodeRegistry registry = new NodeRegistry(List.of(node));
        RunRecorder recorder = mock(RunRecorder.class);
        UUID nodeRunId = UUID.randomUUID();
        when(recorder.startNodeRun(any(), any(), eq(1), any())).thenReturn(nodeRunId);
        RunRequest request = new RunRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Map.of());
        WorkflowDefinition workflow = new WorkflowDefinition("events", "events", 1,
                List.of(new NodeDef("node", node.type(), "node", Map.of(), Set.of(), null, 10)), List.of());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionEngine engine = new ExecutionEngine(new GraphValidator(registry), registry,
                    new SpelConditionEvaluator(), events, recorder, new InMemoryCheckpointStore(),
                    new RunCancellationRegistry(), executor, EngineProperties.defaults());
            assertThat(engine.execute(workflow, request).status()).isEqualTo(RunStatus.SUCCEEDED);
        }
        assertThat(attempted).contains(failedType, RunEventType.RUN_SUCCEEDED);
        verify(recorder).finishNodeRun(eq(nodeRunId), eq(NodeRunStatus.SUCCEEDED), any(), any(), any(Instant.class));
        verify(recorder).finishRun(eq(request.runId()), eq(RunStatus.SUCCEEDED), any(), any(), eq(1));
    }
}
