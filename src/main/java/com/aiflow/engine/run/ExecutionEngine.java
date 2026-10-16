package com.aiflow.engine.run;

import com.aiflow.engine.common.trace.MdcScope;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.graph.ConditionEvaluator;
import com.aiflow.engine.graph.EdgeDef;
import com.aiflow.engine.graph.GraphValidator;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.RetryPolicy;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeRegistry;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.node.RunCanceledException;
import com.aiflow.engine.node.RunControl;
import com.aiflow.engine.tool.ToolAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 执行引擎：拓扑序推进 + 节点级重试/超时 + 运行级记录与事件。
 *
 * <p>入口有两个，共用同一条循环（{@link #run}）：
 * <ul>
 *   <li>{@link #execute} —— 从零开始跑一份定义；</li>
 *   <li>{@link #resume} —— 从一个检查点继续（人工审批等外部输入到达之后）。</li>
 * </ul>
 *
 * <p>恢复的三条硬语义（docs/approval-node-design.md §2）：**不重放已执行节点**、**校验定义版本**、
 * **挂起期间不持任何线程与锁**（本类只负责写出检查点并返回，线程与锁的释放由 RunCoordinator 做）。
 */
@Service
public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final GraphValidator graphValidator;
    private final NodeRegistry nodeRegistry;
    private final ConditionEvaluator conditionEvaluator;
    private final RunEventBus eventBus;
    private final RunRecorder runRecorder;
    private final CheckpointStore checkpointStore;
    private final RunCancellationRegistry cancellationRegistry;
    private final ExecutorService nodeExecutorService;
    private final EngineProperties properties;
    private final RunExecutionRegistry executions;
    private final List<RunResourceLifecycle> resourceLifecycles;

    public ExecutionEngine(GraphValidator graphValidator, NodeRegistry nodeRegistry, ConditionEvaluator conditionEvaluator,
                           RunEventBus eventBus, RunRecorder runRecorder, CheckpointStore checkpointStore,
                           RunCancellationRegistry cancellationRegistry, ExecutorService nodeExecutorService,
                           EngineProperties properties) {
        this(graphValidator, nodeRegistry, conditionEvaluator, eventBus, runRecorder, checkpointStore,
                cancellationRegistry, nodeExecutorService, properties, new RunExecutionRegistry(), List.of());
    }

    public ExecutionEngine(GraphValidator graphValidator, NodeRegistry nodeRegistry, ConditionEvaluator conditionEvaluator,
                           RunEventBus eventBus, RunRecorder runRecorder, CheckpointStore checkpointStore,
                           RunCancellationRegistry cancellationRegistry, ExecutorService nodeExecutorService,
                           EngineProperties properties, RunExecutionRegistry executions) {
        this(graphValidator, nodeRegistry, conditionEvaluator, eventBus, runRecorder, checkpointStore,
                cancellationRegistry, nodeExecutorService, properties, executions, List.of());
    }

    @Autowired
    public ExecutionEngine(GraphValidator graphValidator, NodeRegistry nodeRegistry, ConditionEvaluator conditionEvaluator,
                           RunEventBus eventBus, RunRecorder runRecorder, CheckpointStore checkpointStore,
                           RunCancellationRegistry cancellationRegistry, ExecutorService nodeExecutorService,
                           EngineProperties properties, RunExecutionRegistry executions,
                           List<RunResourceLifecycle> resourceLifecycles) {
        this.graphValidator = graphValidator;
        this.nodeRegistry = nodeRegistry;
        this.conditionEvaluator = conditionEvaluator;
        this.eventBus = eventBus;
        this.runRecorder = runRecorder;
        this.checkpointStore = checkpointStore;
        this.cancellationRegistry = cancellationRegistry;
        this.nodeExecutorService = nodeExecutorService;
        this.properties = properties;
        this.executions = executions;
        this.resourceLifecycles = resourceLifecycles == null ? List.of() : List.copyOf(resourceLifecycles);
    }

    public RunResult execute(WorkflowDefinition definition, RunRequest request) {
        GraphValidator.ValidatedGraph graph = graphValidator.validate(definition);
        RunState state = new RunState(new HashMap<>(request.input()),
                new HashSet<>(graph.entryNodes()), 0, 0);
        return run(definition, request, graph, state, null);
    }

    /**
     * 从检查点继续执行。挂起节点本身**不再执行**，它的结果由调用方（审批决策 / 超时）给出，
     * 并原样并入变量，供下游节点与条件边使用。
     */
    public RunResult resume(WorkflowDefinition definition, RunRequest request, RunCheckpoint checkpoint,
                            NodeResult resumedNodeResult) {
        GraphValidator.ValidatedGraph graph = graphValidator.validate(definition);
        List<String> order = graph.topologicalOrder();
        if (checkpoint.workflowVersion() != definition.version()) {
            throw new IllegalStateException("检查点来自工作流版本 " + checkpoint.workflowVersion()
                    + "，当前定义是版本 " + definition.version() + "：拒绝恢复");
        }
        // 双重校验：版本相同也要确认「断点位置」对得上（拓扑序由定义唯一决定，对不上就是定义被动过）
        if (resumedNodeResult.status() != NodeRunStatus.SUCCEEDED
                && resumedNodeResult.status() != NodeRunStatus.DEGRADED
                && resumedNodeResult.status() != NodeRunStatus.FAILED) {
            throw new IllegalStateException("恢复结果只能是 SUCCEEDED / DEGRADED / FAILED，收到: "
                    + resumedNodeResult.status());
        }
        // 从**断点节点自己**那一格进入循环：它会走「收口而不重新执行」的分支，然后沿用同一条
        // 状态/事件/出边判定路径（DRY 的代价是这里要减 1，写清楚比省一个变量重要）。
        int resumedIndex = checkpoint.nextIndex() - 1;
        RunState state = new RunState(new HashMap<>(checkpoint.variables()), new HashSet<>(checkpoint.activated()),
                resumedIndex, checkpoint.steps());
        return run(definition, request, graph, state, new Resumption(checkpoint, resumedNodeResult, resumedIndex));
    }

    private RunResult run(WorkflowDefinition definition, RunRequest request, GraphValidator.ValidatedGraph graph,
                          RunState state, Resumption resumption) {
        try (RunExecutionRegistry.Scope ignored = executions.bind(request.runId())) {
            return runOwned(definition, request, graph, state, resumption);
        }
    }

    private RunResult runOwned(WorkflowDefinition definition, RunRequest request, GraphValidator.ValidatedGraph graph,
                               RunState state, Resumption resumption) {
        UUID runId = request.runId();
        String runIdText = runId.toString();
        List<String> order = graph.topologicalOrder();
        Map<String, Object> variables = state.variables();
        Set<String> activated = state.activated();
        LoopGuard loopGuard = new LoopGuard(properties.maxSteps(), state.steps());
        int steps = state.steps();
        RunStatus status = RunStatus.RUNNING;
        String failure = null;
        Suspension suspension = null;
        cancellationRegistry.register(runId);
        int resourcesOpened = 0;
        try {
            for (RunResourceLifecycle resource : resourceLifecycles) {
                resource.begin(runId);
                resourcesOpened++;
            }
            throwIfCanceled(runId);
            if (resumption == null) {
                runRecorder.startRun(request, definition);
                eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.RUN_STARTED, null,
                        Map.of("workflowId", definition.id() == null ? "" : definition.id(),
                                "workflowName", definition.name(),
                                "version", definition.version())));
            } else {
                log.info("run {} resumed from checkpoint at node {} (workflow version {})",
                        runId, resumption.checkpoint().nodeId(), definition.version());
            }
            for (int index = state.startIndex(); index < order.size(); index++) {
                throwIfCanceled(runId);
                String nodeId = order.get(index);
                if (!activated.contains(nodeId)) {
                    continue;
                }
                NodeDef node = definition.requireNode(nodeId);
                NodeResult result;
                if (resumption != null && index == resumption.index()) {
                    // 断点节点：不重新执行，只把外部输入的结果收口到它原来那一行 node_runs
                    result = resumption.result();
                    finishResumedNode(runIdText, resumption.checkpoint(), node, result);
                    variables.putAll(result.output());
                } else {
                    steps = loopGuard.step(nodeId);
                    NodeExecution execution = executeNode(request, node, variables);
                    result = execution.result();
                    if (result.status() == NodeRunStatus.WAITING) {
                        suspension = new Suspension(index, node, execution.nodeRunId(), result.output());
                        break;
                    }
                }
                if (result.status() == NodeRunStatus.FAILED) {
                    status = RunStatus.FAILED;
                    failure = result.error();
                    break;
                }
                if (result.status() == NodeRunStatus.DEGRADED) {
                    status = RunStatus.DEGRADED;
                    variables.putIfAbsent("fallback", result.output().getOrDefault("fallback", properties.fallbackReply()));
                }
                for (EdgeDef edge : graph.outgoing().getOrDefault(nodeId, List.of())) {
                    if (conditionEvaluator.evaluate(edge.when(), variables)) {
                        activated.add(edge.to());
                    }
                }
            }
            if (suspension != null) {
                RunCheckpoint checkpoint = new RunCheckpoint(suspension.node().id(), suspension.nodeRunId(),
                        suspension.index() + 1, Set.copyOf(activated), new HashMap<>(variables), steps,
                        definition.version());
                UUID checkpointId = checkpointStore.save(runId, checkpoint);
                runRecorder.markWaiting(runId, suspension.node().id());
                publishRunWaiting(runIdText, suspension, checkpointId);
                Map<String, Object> pendingOutput = extractOutput(variables);
                log.info("run {} is waiting at node {} (checkpoint {})", runId, suspension.node().id(), checkpointId);
                return new RunResult(runId, RunStatus.WAITING_APPROVAL, pendingOutput, null, steps);
            }
            if (status == RunStatus.RUNNING) {
                status = RunStatus.SUCCEEDED;
            }
        } catch (RunLeaseLostException e) {
            // A stale owner must not overwrite the newer owner's nodes or terminal state.
            throw e;
        } catch (LoopDetectedException e) {
            log.warn("run {} hit loop guard: {}", runId, e.getMessage());
            status = RunStatus.FAILED;
            failure = e.getMessage();
            variables.putIfAbsent("fallback", properties.fallbackReply());
        } catch (RunCanceledException e) {
            status = RunStatus.CANCELED;
            failure = e.getMessage();
        } catch (Exception e) {
            log.error("run {} failed unexpectedly", runId, e);
            status = RunStatus.FAILED;
            failure = rootMessage(e);
        } finally {
            for (int i = resourcesOpened - 1; i >= 0; i--) {
                    resourceLifecycles.get(i).end(runId);
            }
            cancellationRegistry.clear(runId);
        }
        Map<String, Object> output = extractOutput(variables);
        runRecorder.finishRun(runId, status, output, failure, steps);
        publishRunFinished(runId, status, output, failure, steps);
        return new RunResult(runId, status, output, failure, steps);
    }

    private NodeExecution executeNode(RunRequest request, NodeDef node, Map<String, Object> variables) {
        RetryPolicy retryPolicy = node.retry() == null
                ? new RetryPolicy(properties.defaultMaxAttempts(), properties.defaultBackoffMillis())
                : node.retry();
        int maxAttempts = Math.max(1, retryPolicy.maxAttempts());
        int timeoutSeconds = node.timeoutSeconds() == null ? properties.defaultNodeTimeoutSeconds() : node.timeoutSeconds();
        String runIdText = request.runId().toString();
        RunExecutionGuard guard = executions.capture(request.runId());
        RunControl runControl = new RunControl() {
            @Override
            public boolean isCanceled() {
                return cancellationRegistry.isCanceled(request.runId());
            }

            @Override
            public void check() {
                guard.check();
                if (isCanceled() || Thread.currentThread().isInterrupted()) {
                    throw new RunCanceledException();
                }
            }
        };
        NodeResult lastResult = null;
        UUID lastNodeRunId = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            throwIfCanceled(request.runId());
            UUID nodeRunId = runRecorder.startNodeRun(request.runId(), node, attempt, new LinkedHashMap<>(variables));
            lastNodeRunId = nodeRunId;
            eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_STARTED, node.id(),
                    Map.of("type", node.type(), "attempt", attempt)));
            Instant startedAt = Instant.now();
            NodeResult result;
            AtomicBoolean attemptOpen = new AtomicBoolean(true);
            RunControl attemptControl = new RunControl() {
                @Override
                public boolean isCanceled() {
                    return !attemptOpen.get() || runControl.isCanceled();
                }

                @Override
                public void check() {
                    try {
                        runControl.check();
                    } catch (RunCanceledException canceled) {
                        // future.cancel(true) interrupts an uncooperative provider. Once the
                        // attempt fence is closed, that interrupt must not leak as a run-level
                        // cancellation; an explicit cancellation in the registry still wins.
                        if (!attemptOpen.get() && !runControl.isCanceled()) {
                            throw new RunAttemptClosedException();
                        }
                        throw canceled;
                    }
                    if (!attemptOpen.get()) {
                        throw new RunAttemptClosedException();
                    }
                }
            };
            try {
                NodeExecutor executor = nodeRegistry.require(node.type());
                NodeContext context = new NodeContext(request.runId(), request.sessionId(), request.userId(),
                        node, variables, eventBus, attemptControl);
                result = executeWithTimeout(executor, context, timeoutSeconds, runControl, attemptOpen);
                attemptControl.check();
            } catch (RunLeaseLostException e) {
                throw e;
            } catch (RunCanceledException e) {
                runRecorder.finishNodeRun(nodeRunId, NodeRunStatus.FAILED, Map.of(), e.getMessage(), Instant.now());
                throw e;
            } catch (Exception e) {
                runControl.check();
                result = NodeResult.failed(rootMessage(e));
            } finally {
                // A provider may ignore interruption and finish after timeout/cancellation. Its context
                // must be fenced before the engine is allowed to retry or continue downstream.
                attemptOpen.set(false);
            }
            lastResult = result;
            long elapsed = Duration.between(startedAt, Instant.now()).toMillis();
            if (result.status() == NodeRunStatus.WAITING) {
                // 挂起不是失败也不是成功：节点已经做完了自己该做的（例如审批单已落库），等的是外部输入。
                // 这里**不消耗重试次数** —— 对人的等待重试没有意义，只会多落几张重复的待办。
                runRecorder.suspendNodeRun(nodeRunId, result.output());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("type", node.type());
                data.put("attempt", attempt);
                data.put("output", result.output());
                eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_SUSPENDED, node.id(), data));
                return new NodeExecution(result, nodeRunId);
            }
            if (result.status() != NodeRunStatus.FAILED) {
                if (result.status() == NodeRunStatus.DEGRADED) {
                    runRecorder.finishNodeRun(nodeRunId, NodeRunStatus.DEGRADED, result.output(), result.error(), Instant.now());
                    eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_DEGRADED, node.id(),
                            Map.of("attempt", attempt, "reason", nullSafe(result.error()), "ms", elapsed)));
                } else {
                    runRecorder.finishNodeRun(nodeRunId, NodeRunStatus.SUCCEEDED, result.output(), null, Instant.now());
                    eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_SUCCEEDED, node.id(),
                            Map.of("attempt", attempt, "ms", elapsed)));
                }
                return new NodeExecution(result, nodeRunId);
            }
            // 策略拒绝是**确定性**失败：同一个用户再试一次结果一样。重试只会多等一轮退避，
            // 还会在审计里留下两行「被拒」——审计记的是尝试次数，一次拒绝被记两次会误导排障。
            boolean lastAttempt = attempt >= maxAttempts || ToolAuthorizer.isDenied(result.error());
            if (!lastAttempt) {
                runRecorder.finishNodeRun(nodeRunId, NodeRunStatus.FAILED, result.output(), result.error(), Instant.now());
                eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_FAILED, node.id(),
                        Map.of("attempt", attempt, "error", nullSafe(result.error()))));
                eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_RETRY, node.id(),
                        Map.of("attempt", attempt, "nextAttempt", attempt + 1)));
                sleepBackoff(retryPolicy.backoffMillis(), request.runId());
                continue;
            }
            String fallback = node.fallback();
            if (fallback != null && !fallback.isBlank()) {
                NodeResult degraded = NodeResult.degraded(Map.of("fallback", fallback), result.error());
                runRecorder.finishNodeRun(nodeRunId, NodeRunStatus.DEGRADED, degraded.output(), result.error(), Instant.now());
                eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_DEGRADED, node.id(),
                        Map.of("attempt", attempt, "reason", nullSafe(result.error()), "fallback", fallback)));
                return new NodeExecution(degraded, nodeRunId);
            }
            runRecorder.finishNodeRun(nodeRunId, NodeRunStatus.FAILED, result.output(), result.error(), Instant.now());
            eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_FAILED, node.id(),
                    Map.of("attempt", attempt, "error", nullSafe(result.error()))));
            return new NodeExecution(result, nodeRunId);
        }
        return new NodeExecution(lastResult == null ? NodeResult.failed("节点未执行") : lastResult, lastNodeRunId);
    }

    /**
     * 断点节点的收口：node_runs 那一行从 WAITING 改成最终状态。
     *
     * <p>不重新发 NODE_STARTED（节点没有重新开始），完成事件的负载里带 {@code resumed=true}，
     * 让控制台能一眼看出「这一步是外部输入推进的」。
     */
    private void finishResumedNode(String runIdText, RunCheckpoint checkpoint, NodeDef node, NodeResult result) {
        runRecorder.finishNodeRun(checkpoint.nodeRunId(), result.status(), result.output(), result.error(), Instant.now());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resumed", true);
        switch (result.status()) {
            case SUCCEEDED -> eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_SUCCEEDED, node.id(), data));
            case DEGRADED -> {
                data.put("reason", nullSafe(result.error()));
                eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_DEGRADED, node.id(), data));
            }
            case FAILED -> {
                data.put("error", nullSafe(result.error()));
                eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.NODE_FAILED, node.id(), data));
            }
            default -> throw new IllegalStateException("恢复节点的结果状态非法: " + result.status());
        }
    }

    private void publishRunWaiting(String runIdText, Suspension suspension, UUID checkpointId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", suspension.node().id());
        data.put("type", suspension.node().type());
        data.put("checkpointId", checkpointId.toString());
        data.put("output", suspension.output());
        eventBus.publishSafely(RunEvent.of(runIdText, RunEventType.RUN_WAITING, null, data));
    }

    private NodeResult executeWithTimeout(NodeExecutor executor, NodeContext context, int timeoutSeconds,
                                          RunControl coordinatorControl, AtomicBoolean attemptOpen) {
        RunExecutionGuard guard = executions.capture(context.runId());
        Future<NodeResult> future = nodeExecutorService.submit(() -> {
            try (MdcScope scope = MdcScope.restore(context.mdc());
                 RunExecutionRegistry.Scope executionScope = executions.bind(context.runId(), guard)) {
                context.throwIfCanceled();
                return executor.execute(context);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (true) {
                coordinatorControl.check();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new NodeTimeoutException("节点执行超时（" + timeoutSeconds + "s）");
                }
                try {
                    NodeResult result = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                            TimeUnit.NANOSECONDS);
                    coordinatorControl.check();
                    return result;
                } catch (TimeoutException ignored) {
                    // Poll ownership even while an uncooperative provider is still waiting for I/O.
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RunCanceledException canceled) {
                throw canceled;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause == null ? "节点执行失败" : String.valueOf(cause.getMessage()), cause);
        } catch (InterruptedException e) {
            attemptOpen.set(false);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        } catch (RuntimeException e) {
            // Close before interrupting: an uncooperative provider must observe the fence even if
            // it catches InterruptedException and continues on the worker thread.
            attemptOpen.set(false);
            future.cancel(true);
            throw e;
        }
    }

    private void sleepBackoff(long backoffMillis, UUID runId) {
        if (backoffMillis <= 0) {
            return;
        }
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMillis);
            while (true) {
                throwIfCanceled(runId);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        }
        throwIfCanceled(runId);
    }

    private void throwIfCanceled(UUID runId) {
        executions.check(runId);
        if (cancellationRegistry.isCanceled(runId) || Thread.currentThread().isInterrupted()) {
            throw new RunCanceledException();
        }
    }

    private Map<String, Object> extractOutput(Map<String, Object> variables) {
        Object output = variables.get("__output__");
        if (output instanceof Map<?, ?> mapping) {
            Map<String, Object> result = new LinkedHashMap<>();
            mapping.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        variables.forEach((key, value) -> {
            if (!key.startsWith("__")) {
                result.put(key, value);
            }
        });
        return result;
    }

    private void publishRunFinished(UUID runId, RunStatus status, Map<String, Object> output, String failure, int steps) {
        RunEventType type = switch (status) {
            case SUCCEEDED -> RunEventType.RUN_SUCCEEDED;
            case DEGRADED -> RunEventType.RUN_DEGRADED;
            case CANCELED -> RunEventType.RUN_CANCELED;
            case FAILED -> RunEventType.RUN_FAILED;
            // 非终态走到这里就是引擎自身写错了：宁可抛出来，也不要打一条「运行失败」把挂起粉饰成失败
            case PENDING, RUNNING, WAITING_APPROVAL -> throw new IllegalStateException("非终态不发布收尾事件: " + status);
        };
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.name());
        data.put("steps", steps);
        data.put("output", output);
        if (failure != null) {
            data.put("error", failure);
        }
        eventBus.publishSafely(RunEvent.of(runId.toString(), type, null, data));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record RunState(Map<String, Object> variables, Set<String> activated, int startIndex, int steps) {
    }

    private record Resumption(RunCheckpoint checkpoint, NodeResult result, int index) {
    }

    private record NodeExecution(NodeResult result, UUID nodeRunId) {
    }

    private record Suspension(int index, NodeDef node, UUID nodeRunId, Map<String, Object> output) {
    }
}
