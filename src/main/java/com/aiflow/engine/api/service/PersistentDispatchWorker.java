package com.aiflow.engine.api.service;

import com.aiflow.engine.approval.ApprovalResumeService;
import com.aiflow.engine.approval.ApprovalService;
import com.aiflow.engine.config.DispatchProperties;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.run.RunLeaseLostException;
import com.aiflow.engine.run.RunStatus;
import com.aiflow.engine.session.DispatchKind;
import com.aiflow.engine.session.DispatchLease;
import com.aiflow.engine.session.DispatchStatus;
import com.aiflow.engine.session.DispatchTaskStore;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** The database queue survives process exits; only claimed execution segments occupy workers. */
@Service
public class PersistentDispatchWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PersistentDispatchWorker.class);
    private final DispatchTaskStore tasks;
    private final DispatchProperties properties;
    private final RunCoordinator coordinator;
    private final ApprovalService approvals;
    private final ApprovalResumeService approvalResults;
    private final WorkflowRunRepository runs;
    private final RunEventBus events;
    private final ExecutorService executor;
    private final MeterRegistry meters;
    private final Semaphore slots;
    private final ScheduledExecutorService renewals;
    private final Set<AtomicBoolean> activeLeases = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong claimed = new AtomicLong();
    private final AtomicLong recovered = new AtomicLong();
    private final AtomicLong leaseLost = new AtomicLong();

    @Autowired
    public PersistentDispatchWorker(DispatchTaskStore tasks, DispatchProperties properties,
                                    RunCoordinator coordinator, ApprovalService approvals,
                                    ApprovalResumeService approvalResults, WorkflowRunRepository runs,
                                    RunEventBus events, ExecutorService executor, MeterRegistry meters) {
        this(tasks, properties, coordinator, approvals, approvalResults, runs, events, executor, meters,
                Executors.newScheduledThreadPool(1,
                        Thread.ofPlatform().daemon().name("dispatch-renew-", 0).factory()));
    }

    PersistentDispatchWorker(DispatchTaskStore tasks, DispatchProperties properties,
                             RunCoordinator coordinator, ApprovalService approvals,
                             ApprovalResumeService approvalResults, WorkflowRunRepository runs,
                             RunEventBus events, ExecutorService executor, MeterRegistry meters,
                             ScheduledExecutorService renewals) {
        this.tasks = tasks;
        this.properties = properties;
        this.coordinator = coordinator;
        this.approvals = approvals;
        this.approvalResults = approvalResults;
        this.runs = runs;
        this.events = events;
        this.executor = executor;
        this.meters = meters;
        this.slots = new Semaphore(properties.workers());
        this.renewals = renewals;
    }

    @Scheduled(fixedDelayString = "${aiflow.dispatch.poll-ms:200}")
    public void poll() {
        if (closed.get() || !properties.enabled() || !properties.workerEnabled()) {
            return;
        }
        try {
            recovered.addAndGet(tasks.reapExpired(properties.recoveryBatchSize(), coordinator::reconcileExpired));
            while (slots.tryAcquire()) {
                java.util.Optional<DispatchLease> candidate;
                try {
                    candidate = tasks.claimNext(UUID.randomUUID().toString(), properties.lease());
                } catch (RuntimeException failure) {
                    slots.release();
                    throw failure;
                }
                if (candidate.isEmpty()) {
                    slots.release();
                    break;
                }
                try {
                    executor.execute(() -> execute(candidate.get()));
                } catch (RuntimeException failure) {
                    try {
                        tasks.requeueBeforeExecution(candidate.get());
                    } finally {
                        slots.release();
                    }
                    throw failure;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("durable dispatch polling unavailable: {}", failure.getClass().getSimpleName());
        }
    }

    private void execute(DispatchLease lease) {
        LeaseLifecycle lifecycle = new LeaseLifecycle();
        activeLeases.add(lifecycle.healthy);
        ScheduledFuture<?> renewal = null;
        try {
            claimed.incrementAndGet();
            meters.counter("aiflow.dispatch.claimed").increment();
            meters.timer("aiflow.dispatch.queue.wait").record(Duration.ofMillis(Math.max(0,
                    Duration.between(lease.task().createdAt(), Instant.now()).toMillis())));
            long interval = Math.max(100, properties.lease().toMillis() / 3);
            renewal = renewals.scheduleWithFixedDelay(() -> renew(lease, lifecycle),
                    interval, interval, TimeUnit.MILLISECONDS);
            NodeResult result = null;
            if (lease.task().kind() == DispatchKind.RESUME) {
                var approval = approvals.find(lease.task().approvalId()).orElseThrow();
                result = approvalResults.toNodeResult(approval);
            }
            if (coordinator.executeTask(lease, result, lifecycle.healthy::get)) {
                finalizeLease(lifecycle, () -> tasks.complete(lease));
            } else {
                finalizeLease(lifecycle, () -> tasks.requeueBeforeExecution(lease));
                meters.counter("aiflow.dispatch.lock.busy").increment();
            }
        } catch (RunLeaseLostException failure) {
            markLeaseLost(lifecycle);
            // Reconciliation holds the task row and closes facts; never replay an uncertain segment.
            try {
                closeOwnedFailure(lease, lifecycle, "执行租约失效，未自动重放");
            } catch (RunLeaseLostException lost) {
                tasks.reapExpired(properties.recoveryBatchSize(), coordinator::reconcileExpired);
            } catch (RuntimeException unavailable) {
                // The next poll retries recovery when the database is available again.
                log.warn("dispatch failure reconciliation unavailable: task={} cause={}", lease.taskId(),
                        unavailable.getClass().getSimpleName());
            }
        } catch (RuntimeException failure) {
            try {
                if (renewal == null) {
                    // No engine call began: keep the original queue position for the next worker.
                    finalizeLease(lifecycle, () -> tasks.requeueBeforeExecution(lease));
                } else {
                    closeOwnedFailure(lease, lifecycle, "执行段失败: " + failure.getClass().getSimpleName());
                }
            } catch (RunLeaseLostException lost) {
                markLeaseLost(lifecycle);
                // A newer recovery owner is authoritative.
            } catch (RuntimeException unavailable) {
                log.warn("dispatch failure reconciliation unavailable: task={} cause={}", lease.taskId(),
                        unavailable.getClass().getSimpleName());
            }
            log.warn("dispatch execution failed: task={} cause={}", lease.taskId(), failure.getClass().getSimpleName());
        } finally {
            // cancel(false) cannot stop a callback already executing or waiting for this lock.
            lifecycle.lock.lock();
            try {
                lifecycle.finished = true;
                lifecycle.healthy.set(false);
            } finally {
                lifecycle.lock.unlock();
            }
            if (renewal != null) {
                renewal.cancel(false);
            }
            activeLeases.remove(lifecycle.healthy);
            slots.release();
        }
    }

    private void renew(DispatchLease lease, LeaseLifecycle lifecycle) {
        lifecycle.lock.lock();
        try {
            if (lifecycle.finished || !lifecycle.healthy.get()) return;
            try {
                if (!tasks.renew(lease, properties.lease())) markLeaseLost(lifecycle);
            } catch (RuntimeException failure) {
                markLeaseLost(lifecycle);
                log.warn("dispatch lease renewal unavailable: task={}", lease.taskId());
            }
        } finally {
            lifecycle.lock.unlock();
        }
    }

    private void markLeaseLost(LeaseLifecycle lifecycle) {
        lifecycle.lock.lock();
        try {
            if (!lifecycle.finished && lifecycle.healthy.compareAndSet(true, false)) {
                leaseLost.incrementAndGet();
                meters.counter("aiflow.dispatch.lease.lost").increment();
            }
        } finally {
            lifecycle.lock.unlock();
        }
    }

    private void finalizeLease(LeaseLifecycle lifecycle, BooleanSupplier finish) {
        lifecycle.lock.lock();
        try {
            if (!lifecycle.healthy.get() || !finish.getAsBoolean()) {
                throw new RunLeaseLostException("执行任务收口时租约已失效");
            }
            lifecycle.finished = true;
            lifecycle.healthy.set(false);
        } finally {
            lifecycle.lock.unlock();
        }
    }

    private void closeOwnedFailure(DispatchLease lease, LeaseLifecycle lifecycle, String error) {
        lifecycle.lock.lock();
        try {
            // This recovery path still requires the PostgreSQL ownership fence, even if
            // an execution guard was invalidated by Redis/heartbeat failure.
            tasks.guardWrite(lease, () -> {
                boolean alreadyCommitted = coordinator.segmentOutcomeCommitted(lease.task());
                coordinator.reconcileExpired(lease.task());
                boolean finished = alreadyCommitted ? tasks.complete(lease) : tasks.fail(lease, error);
                if (!finished) throw new RunLeaseLostException("执行任务失败收口时租约已失效");
                return null;
            });
            lifecycle.finished = true;
            lifecycle.healthy.set(false);
        } finally {
            lifecycle.lock.unlock();
        }
    }

    public boolean cancel(UUID runId, String actor) {
        if (!properties.enabled()) {
            return false;
        }
        var run = runs.findById(runId).orElse(null);
        if (run == null) {
            return false;
        }
        if (run.getStatus() == RunStatus.WAITING_APPROVAL) {
            if (coordinator.cancelWaiting(runId, actor)) {
                tasks.cancelQueued(runId, task -> { });
                return true;
            }
            // The resume may have won its WAITING -> RUNNING CAS while cancellation waited.
            run = runs.findById(runId).orElse(null);
            if (run == null) {
                return false;
            }
        }
        if (run.getStatus() != RunStatus.PENDING && run.getStatus() != RunStatus.RUNNING) {
            return false;
        }
        int canceled = tasks.cancelQueued(runId, coordinator::cancelQueued);
        if (canceled > 0) {
            events.publishSafely(RunEvent.of(runId.toString(), RunEventType.RUN_CANCELED, null,
                    Map.of("status", "CANCELED", "steps", 0, "output", Map.of(), "error", "排队期间取消")));
            return true;
        }
        return tasks.requestCancel(runId).stream().anyMatch(task -> task.status() == DispatchStatus.RUNNING);
    }

    public Status status() {
        return new Status(properties.enabled(), properties.workerEnabled(),
                properties.workers() - slots.availablePermits(), claimed.get(), recovered.get(), leaseLost.get());
    }

    @PreDestroy
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            activeLeases.forEach(flag -> flag.set(false));
            renewals.shutdownNow();
        }
    }

    public record Status(boolean enabled, boolean workerEnabled, int activeWorkers,
                         long claimed, long recovered, long leaseLost) { }

    private static final class LeaseLifecycle {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicBoolean healthy = new AtomicBoolean(true);
        private boolean finished;
    }
}
