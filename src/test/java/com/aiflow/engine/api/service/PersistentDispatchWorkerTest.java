package com.aiflow.engine.api.service;

import com.aiflow.engine.approval.ApprovalResumeService;
import com.aiflow.engine.approval.ApprovalService;
import com.aiflow.engine.config.DispatchProperties;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.persistence.entity.WorkflowRunEntity;
import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.run.RunLeaseLostException;
import com.aiflow.engine.run.RunStatus;
import com.aiflow.engine.session.DispatchKind;
import com.aiflow.engine.session.DispatchLease;
import com.aiflow.engine.session.DispatchStatus;
import com.aiflow.engine.session.DispatchTask;
import com.aiflow.engine.session.DispatchTaskStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentDispatchWorkerTest {

    @Test
    void busyRedisLockRequeuesBeforeExecutionWithoutLosingTheWorkerSlot() {
        Fixture fixture = new Fixture();
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenReturn(false);
        try (var worker = fixture.worker()) {
            worker.poll();
            assertThat(worker.status().activeWorkers()).isZero();
            verify(fixture.tasks).requeueBeforeExecution(fixture.lease);
            verify(fixture.tasks, never()).complete(any());
            verify(fixture.tasks, never()).fail(any(), anyString());
            verify(fixture.scheduled).cancel(false);
            fixture.renewal.get().run();
            assertThat(worker.status().leaseLost()).isZero();
            verify(fixture.tasks, never()).renew(any(), any());
        }
    }

    @Test
    void lateRenewalAfterCommittedCompletionDoesNotVisitPgOrCountLeaseLoss() {
        Fixture fixture = new Fixture();
        AtomicReference<BooleanSupplier> guard = new AtomicReference<>();
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    guard.set(invocation.getArgument(2));
                    return true;
                });
        when(fixture.tasks.renew(any(), any())).thenReturn(false);
        try (var worker = fixture.worker()) {
            worker.poll();
            fixture.renewal.get().run();
            fixture.renewal.get().run();
            assertThat(guard.get().getAsBoolean()).isFalse();
            assertThat(worker.status().leaseLost()).isZero();
            assertThat(worker.status().activeWorkers()).isZero();
            verify(fixture.tasks).complete(fixture.lease);
            verify(fixture.tasks, never()).renew(any(), any());
            verify(fixture.tasks, never()).fail(any(), anyString());
        }
    }

    @Test
    void renewalQueuedDuringCompletionSkipsPgAfterCompletionCommits() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch completing = new CountDownLatch(1);
        CountDownLatch releaseCompletion = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class))).thenReturn(true);
        when(fixture.tasks.complete(fixture.lease)).thenAnswer(invocation -> {
            completing.countDown();
            assertThat(releaseCompletion.await(3, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        // A callback reaching PG after completion would correctly see no RUNNING owner.
        when(fixture.tasks.renew(any(), any())).thenReturn(false);
        try (var worker = fixture.worker()) {
            Thread execution = startThread(threadFailure, worker::poll);
            assertThat(completing.await(2, TimeUnit.SECONDS)).isTrue();
            Thread renewal = startThread(threadFailure, () -> fixture.renewal.get().run());
            try {
                awaitLockWait(renewal);
                verify(fixture.tasks, never()).renew(any(), any());
            } finally {
                releaseCompletion.countDown();
            }
            joinThread(execution, threadFailure);
            joinThread(renewal, threadFailure);
            assertThat(worker.status().leaseLost()).isZero();
            assertThat(worker.status().activeWorkers()).isZero();
            verify(fixture.tasks, never()).renew(any(), any());
            verify(fixture.tasks).complete(fixture.lease);
        }
    }

    @Test
    void completionWaitsForInFlightRenewalWithoutBlockingTheExecutionSegment() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch renewing = new CountDownLatch(1);
        CountDownLatch releaseRenewal = new CountDownLatch(1);
        CountDownLatch segmentFinished = new CountDownLatch(1);
        AtomicReference<Thread> renewalThread = new AtomicReference<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        when(fixture.tasks.renew(any(), any())).thenAnswer(invocation -> {
            renewing.countDown();
            assertThat(releaseRenewal.await(3, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    renewalThread.set(startThread(threadFailure, () -> fixture.renewal.get().run()));
                    assertThat(renewing.await(2, TimeUnit.SECONDS)).isTrue();
                    // The renewal entered during executeTask, so the lifecycle lock
                    // cannot have been held across the entire execution segment.
                    assertThat(invocation.getArgument(2, BooleanSupplier.class).getAsBoolean()).isTrue();
                    segmentFinished.countDown();
                    return true;
                });
        when(fixture.tasks.complete(fixture.lease)).thenAnswer(invocation -> {
            completions.incrementAndGet();
            return true;
        });
        try (var worker = fixture.worker()) {
            Thread execution = startThread(threadFailure, worker::poll);
            try {
                assertThat(segmentFinished.await(2, TimeUnit.SECONDS)).isTrue();
                awaitLockWait(execution);
                assertThat(completions).hasValue(0);
            } finally {
                releaseRenewal.countDown();
            }
            joinThread(execution, threadFailure);
            joinThread(renewalThread.get(), threadFailure);
            fixture.renewal.get().run();
            var order = inOrder(fixture.tasks);
            order.verify(fixture.tasks).renew(eq(fixture.lease), any(Duration.class));
            order.verify(fixture.tasks).complete(fixture.lease);
            verify(fixture.tasks, times(1)).renew(any(), any());
            assertThat(worker.status().leaseLost()).isZero();
            assertThat(worker.status().activeWorkers()).isZero();
        }
    }

    @Test
    void renewalQueuedDuringFencedFailureFinalizationDoesNotReportLeaseLoss() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch failing = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenThrow(new IllegalStateException("synthetic execution failure"));
        when(fixture.tasks.fail(eq(fixture.lease), anyString())).thenAnswer(invocation -> {
            assertThat(fixture.insideFence).isTrue();
            failing.countDown();
            assertThat(releaseFailure.await(3, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        when(fixture.tasks.renew(any(), any())).thenReturn(false);
        try (var worker = fixture.worker()) {
            Thread execution = startThread(threadFailure, worker::poll);
            assertThat(failing.await(2, TimeUnit.SECONDS)).isTrue();
            Thread renewal = startThread(threadFailure, () -> fixture.renewal.get().run());
            try {
                awaitLockWait(renewal);
            } finally {
                releaseFailure.countDown();
            }
            joinThread(execution, threadFailure);
            joinThread(renewal, threadFailure);
            verify(fixture.tasks, never()).renew(any(), any());
            verify(fixture.tasks).fail(eq(fixture.lease), anyString());
            assertThat(worker.status().leaseLost()).isZero();
        }
    }

    @Test
    void activeRenewalRejectionInvalidatesGuardAndPreventsNormalCompletion() {
        Fixture fixture = new Fixture();
        when(fixture.tasks.renew(any(), any())).thenReturn(false);
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    fixture.renewal.get().run();
                    fixture.renewal.get().run();
                    assertThat(invocation.getArgument(2, BooleanSupplier.class).getAsBoolean()).isFalse();
                    // Even a coordinator returning normally cannot finish through a lost guard.
                    return true;
                });
        try (var worker = fixture.worker()) {
            worker.poll();
            assertThat(worker.status().leaseLost()).isEqualTo(1);
            assertThat(worker.status().activeWorkers()).isZero();
            verify(fixture.tasks, times(1)).renew(any(), any());
            verify(fixture.tasks, never()).complete(any());
            verify(fixture.tasks).guardWrite(eq(fixture.lease), any());
            verify(fixture.tasks).fail(eq(fixture.lease), anyString());
        }
    }

    @Test
    void executionFailureWithLostPgFenceStillCountsLeaseLossOnce() {
        Fixture fixture = new Fixture();
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenThrow(new IllegalStateException("synthetic execution failure"));
        doThrow(new RunLeaseLostException("replacement owner")).when(fixture.tasks).guardWrite(any(), any());
        try (var worker = fixture.worker()) {
            worker.poll();
            fixture.renewal.get().run();
            assertThat(worker.status().leaseLost()).isEqualTo(1);
            assertThat(worker.status().activeWorkers()).isZero();
            verify(fixture.tasks, never()).renew(any(), any());
            verify(fixture.coordinator, never()).reconcileExpired(any());
            verify(fixture.tasks, never()).complete(any());
            verify(fixture.tasks, never()).fail(any(), anyString());
        }
    }

    @Test
    void rejectedFailureFinalizationStillCountsLeaseLoss() {
        Fixture fixture = new Fixture();
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenThrow(new IllegalStateException("synthetic execution failure"));
        when(fixture.tasks.fail(eq(fixture.lease), anyString())).thenReturn(false);
        try (var worker = fixture.worker()) {
            worker.poll();
            fixture.renewal.get().run();
            assertThat(worker.status().leaseLost()).isEqualTo(1);
            assertThat(worker.status().activeWorkers()).isZero();
            verify(fixture.tasks).guardWrite(eq(fixture.lease), any());
            verify(fixture.tasks).fail(eq(fixture.lease), anyString());
            verify(fixture.tasks, never()).complete(any());
            verify(fixture.tasks, never()).renew(any(), any());
        }
    }

    @Test
    void rejectedCompletionCountsLeaseLossAndLeavesUnownedTerminalWritesToReaper() {
        Fixture fixture = new Fixture();
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class))).thenReturn(true);
        when(fixture.tasks.complete(fixture.lease)).thenReturn(false);
        when(fixture.coordinator.segmentOutcomeCommitted(fixture.lease.task())).thenReturn(true);
        try (var worker = fixture.worker()) {
            worker.poll();
            fixture.renewal.get().run();
            assertThat(worker.status().leaseLost()).isEqualTo(1);
            assertThat(worker.status().activeWorkers()).isZero();
            verify(fixture.tasks, times(2)).complete(fixture.lease);
            verify(fixture.tasks).guardWrite(eq(fixture.lease), any());
            verify(fixture.tasks, times(2)).reapExpired(anyInt(), any());
            verify(fixture.tasks, never()).fail(any(), anyString());
            verify(fixture.tasks, never()).renew(any(), any());
        }
    }

    @Test
    void heartbeatFailureStopsOwnershipAndClosesRunAndTaskInOneFence() {
        Fixture fixture = new Fixture();
        when(fixture.tasks.renew(any(), any())).thenThrow(new IllegalStateException("Redis-free PG outage"));
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    fixture.renewal.get().run();
                    fixture.renewal.get().run();
                    assertThat(invocation.getArgument(2, BooleanSupplier.class).getAsBoolean()).isFalse();
                    throw new RunLeaseLostException("heartbeat unavailable");
                });
        doAnswer(invocation -> {
            assertThat(fixture.insideFence).isTrue();
            return null;
        }).when(fixture.coordinator).reconcileExpired(fixture.lease.task());
        when(fixture.tasks.fail(eq(fixture.lease), anyString())).thenAnswer(invocation -> {
            assertThat(fixture.insideFence).isTrue();
            return true;
        });
        try (var worker = fixture.worker()) {
            worker.poll();
            assertThat(worker.status().leaseLost()).isEqualTo(1);
            assertThat(worker.status().activeWorkers()).isZero();
        }
        verify(fixture.tasks, times(1)).renew(any(), any());
        verify(fixture.coordinator).reconcileExpired(fixture.lease.task());
        verify(fixture.tasks).fail(eq(fixture.lease), anyString());
    }

    @Test
    void redisLossWithValidPgLeaseClosesTheUncertainExecution() {
        Fixture fixture = new Fixture();
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenThrow(new RunLeaseLostException("session lock lost"));
        try (var worker = fixture.worker()) {
            worker.poll();
            assertThat(worker.status().leaseLost()).isEqualTo(1);
        }
        verify(fixture.coordinator).reconcileExpired(fixture.lease.task());
        verify(fixture.tasks).fail(eq(fixture.lease), anyString());
    }

    @Test
    void invalidPgLeaseLeavesRunWritesToTheReaper() {
        Fixture fixture = new Fixture();
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenThrow(new RunLeaseLostException("owner replaced"));
        doThrow(new RunLeaseLostException("not owner")).when(fixture.tasks).guardWrite(any(), any());
        try (var worker = fixture.worker()) {
            worker.poll();
        }
        verify(fixture.coordinator, never()).reconcileExpired(any());
        verify(fixture.tasks, never()).fail(any(), anyString());
        verify(fixture.tasks, times(2)).reapExpired(anyInt(), any());
    }

    @Test
    void committedTerminalOutcomeSurvivesLateInfrastructureFailure() {
        Fixture fixture = new Fixture();
        when(fixture.coordinator.executeTask(eq(fixture.lease), isNull(), any(BooleanSupplier.class)))
                .thenThrow(new RunLeaseLostException("lost after terminal commit"));
        when(fixture.coordinator.segmentOutcomeCommitted(fixture.lease.task())).thenReturn(true);
        try (var worker = fixture.worker()) {
            worker.poll();
        }
        verify(fixture.tasks).complete(fixture.lease);
        verify(fixture.tasks, never()).fail(any(), anyString());
    }

    @Test
    void unavailableRenewalSchedulerRequeuesBeforeExecutionAndReleasesSlot() {
        Fixture fixture = new Fixture();
        doThrow(new RejectedExecutionException("closed scheduler")).when(fixture.scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        try (var worker = fixture.worker()) {
            worker.poll();
            assertThat(worker.status().activeWorkers()).isZero();
        }
        verify(fixture.tasks).requeueBeforeExecution(fixture.lease);
        verify(fixture.coordinator, never()).executeTask(any(), any(), any());
    }

    @Test
    void cancelRetriesRunningPathWhenResumeWinsTheWaitingStateRace() {
        Fixture fixture = new Fixture();
        WorkflowRunEntity waiting = new WorkflowRunEntity();
        waiting.setId(fixture.lease.runId());
        waiting.setStatus(RunStatus.WAITING_APPROVAL);
        WorkflowRunEntity running = new WorkflowRunEntity();
        running.setId(fixture.lease.runId());
        running.setStatus(RunStatus.RUNNING);
        when(fixture.runs.findById(fixture.lease.runId())).thenReturn(Optional.of(waiting), Optional.of(running));
        when(fixture.tasks.requestCancel(fixture.lease.runId())).thenReturn(List.of(fixture.lease.task()));
        try (var worker = fixture.worker()) {
            assertThat(worker.cancel(fixture.lease.runId(), "tester")).isTrue();
        }
        verify(fixture.tasks).requestCancel(fixture.lease.runId());
    }

    private static Thread startThread(AtomicReference<Throwable> failure, Runnable action) {
        return Thread.ofVirtual().start(() -> {
            try { action.run(); } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
        });
    }

    private static void joinThread(Thread thread, AtomicReference<Throwable> failure) throws InterruptedException {
        assertThat(thread).isNotNull();
        thread.join(3000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    private static void awaitLockWait(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.isAlive()).isTrue();
        assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
    }

    private static final class Fixture {
        private final DispatchTaskStore tasks = mock(DispatchTaskStore.class);
        private final RunCoordinator coordinator = mock(RunCoordinator.class);
        private final ApprovalService approvals = mock(ApprovalService.class);
        private final ApprovalResumeService approvalResults = mock(ApprovalResumeService.class);
        private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
        private final RunEventBus events = mock(RunEventBus.class);
        private final ExecutorService executor = mock(ExecutorService.class);
        private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        private final ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final AtomicReference<Runnable> renewal = new AtomicReference<>();
        private final AtomicBoolean insideFence = new AtomicBoolean();
        private final DispatchProperties properties = new DispatchProperties(true, true, 1, 200,
                Duration.ofSeconds(30), 50);
        private final DispatchLease lease;

        @SuppressWarnings("unchecked")
        private Fixture() {
            Instant now = Instant.now();
            lease = new DispatchLease(new DispatchTask(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    1, DispatchKind.PROCESS, null, null, "test", DispatchStatus.RUNNING, "owner",
                    now.plusSeconds(30), null, now, now, now, null, null), "owner");
            when(tasks.claimNext(anyString(), any(Duration.class)))
                    .thenReturn(Optional.of(lease), Optional.empty());
            when(tasks.complete(any())).thenReturn(true);
            when(tasks.requeueBeforeExecution(any())).thenReturn(true);
            when(tasks.fail(any(), anyString())).thenReturn(true);
            doAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return null;
            }).when(executor).execute(any(Runnable.class));
            doAnswer(invocation -> {
                renewal.set(invocation.getArgument(0));
                return scheduled;
            }).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(),
                    any(TimeUnit.class));
            doAnswer(invocation -> {
                insideFence.set(true);
                try {
                    return invocation.getArgument(1, Supplier.class).get();
                } finally {
                    insideFence.set(false);
                }
            }).when(tasks).guardWrite(any(), any());
        }

        private PersistentDispatchWorker worker() {
            return new PersistentDispatchWorker(tasks, properties, coordinator, approvals, approvalResults,
                    runs, events, executor, meters, scheduler);
        }
    }
}
