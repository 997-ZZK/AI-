package com.aiflow.engine.persistence.dispatch;

import com.aiflow.engine.persistence.repo.WorkflowRunRepository;
import com.aiflow.engine.run.RunLeaseLostException;
import com.aiflow.engine.run.RunStatus;
import com.aiflow.engine.session.DispatchKind;
import com.aiflow.engine.session.DispatchLease;
import com.aiflow.engine.session.DispatchStatus;
import com.aiflow.engine.session.DispatchTask;
import com.aiflow.engine.session.DispatchTaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H2 verifies transactional contracts; PostgreSQL SKIP LOCKED is verified in the real-stack runner. */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dispatch-store;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PgDispatchTaskStore.class, PgDispatchTaskStoreIntegrationTest.JdbcConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PgDispatchTaskStoreIntegrationTest {
    private static final Duration TTL = Duration.ofMinutes(1);

    @Autowired
    private DispatchTaskStore store;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WorkflowRunRepository runs;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanContractDatabase() {
        // This isolated in-memory database has no application workers or shared integration context.
        jdbc.update("delete from session_dispatch_tasks");
        jdbc.update("delete from workflow_runs");
        jdbc.update("delete from sessions");
    }

    @Test
    void admissionMustJoinTheBusinessTransaction() {
        assertThatThrownBy(() -> store.enqueueProcess(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
    }

    @Test
    void failedAcceptanceRollsBackRunAndTaskTogether() {
        UUID session = session();
        UUID run = UUID.randomUUID();
        assertThatThrownBy(() -> tx(() -> {
            insertRun(run, session);
            store.enqueueProcess(session, run);
            throw new IllegalStateException("capacity rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.findByRun(run)).isEmpty();
        assertThat(runs.findById(run)).isEmpty();
    }

    @Test
    void processAndResumeDeduplicateAndKeepTheirOwnSequence() {
        UUID session = session();
        UUID run = run(session);
        DispatchTask process = enqueue(session, run);
        DispatchTask duplicate = enqueue(session, run);
        assertThat(duplicate.id()).isEqualTo(process.id());

        UUID checkpoint = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        DispatchTask resumed = tx(() -> store.enqueueResume(session, run, checkpoint, approval));
        assertThat(tx(() -> store.enqueueResume(session, run, checkpoint, approval)).id())
                .isEqualTo(resumed.id());
        assertThat(resumed.kind()).isEqualTo(DispatchKind.RESUME);
        assertThat(resumed.checkpointId()).isEqualTo(checkpoint);
        assertThat(resumed.approvalId()).isEqualTo(approval);
        assertThat(resumed.seq()).isGreaterThan(process.seq());
        assertThat(store.countOutstanding(session)).isEqualTo(2);
    }

    @Test
    void onlyTheOldestUnfinishedSegmentOfASessionCanBeClaimed() {
        UUID session = session();
        DispatchTask first = enqueue(session, run(session));
        DispatchTask second = enqueue(session, run(session));
        DispatchLease firstLease = claim();
        assertThat(firstLease.taskId()).isEqualTo(first.id());
        assertThat(store.claimNext(owner(), TTL)).isEmpty();

        UUID otherSession = session();
        DispatchTask other = enqueue(otherSession, run(otherSession));
        assertThat(claim().taskId()).isEqualTo(other.id());
        assertThat(store.complete(firstLease)).isTrue();
        assertThat(claim().taskId()).isEqualTo(second.id());
    }

    @Test
    void lockContentionRequeuesBeforeExecutionWithoutChangingSequence() {
        UUID session = session();
        DispatchTask task = enqueue(session, run(session));
        DispatchLease first = claim();
        assertThat(store.requeueBeforeExecution(first)).isTrue();
        assertThat(store.find(task.id()).orElseThrow().seq()).isEqualTo(task.seq());
        DispatchLease second = claim();
        assertThat(second.taskId()).isEqualTo(task.id());
        assertThat(second.ownerToken()).isNotEqualTo(first.ownerToken());
        assertThat(store.renew(first, TTL)).isFalse();
        assertThat(store.complete(first)).isFalse();
        assertThat(store.fail(first, "stale")).isFalse();
        assertThat(store.renew(second, TTL)).isTrue();
    }

    @Test
    void guardedJpaWritesCommitAndCancellationStillAllowsTheOwnerToCloseTheRun() {
        UUID session = session();
        UUID run = run(session);
        enqueue(session, run);
        DispatchLease lease = claim();
        store.requestCancel(run);
        assertThat(store.find(lease.taskId()).orElseThrow().cancelRequestedAt()).isNotNull();
        store.guardWrite(lease, () -> {
            var row = runs.findById(run).orElseThrow();
            row.setStatus(RunStatus.CANCELED);
            runs.save(row);
            assertThat(store.complete(lease)).isTrue();
            return null;
        });
        assertThat(runs.findById(run).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELED);
        assertThat(store.find(lease.taskId()).orElseThrow().status()).isEqualTo(DispatchStatus.DONE);
    }

    @Test
    void replacedOwnerCannotWriteOrRenewAndTheCallbackIsNotExecuted() {
        UUID session = session();
        enqueue(session, run(session));
        DispatchLease lease = claim();
        jdbc.update("update session_dispatch_tasks set owner_token = ? where id = ?", owner(), lease.taskId());
        AtomicBoolean called = new AtomicBoolean();
        assertThatThrownBy(() -> store.guardWrite(lease, () -> {
            called.set(true);
            return null;
        })).isInstanceOf(RunLeaseLostException.class);
        assertThat(called).isFalse();
        assertThat(store.renew(lease, TTL)).isFalse();
        assertThat(store.complete(lease)).isFalse();
    }

    @Test
    void callbackCannotExpireItsLeaseAndStillCommitJpaFacts() {
        UUID session = session();
        UUID run = run(session);
        enqueue(session, run);
        DispatchLease lease = claim();
        assertThatThrownBy(() -> store.guardWrite(lease, () -> {
            var row = runs.findById(run).orElseThrow();
            row.setError("must roll back");
            runs.save(row);
            expire(lease);
            return null;
        })).isInstanceOf(RunLeaseLostException.class);
        assertThat(runs.findById(run).orElseThrow().getError()).isNull();
        assertThat(store.renew(lease, TTL)).isTrue();
    }

    @Test
    void elapsedLeaseDuringCallbackRollsBackItsJpaWrites() {
        UUID session = session();
        UUID run = run(session);
        enqueue(session, run);
        DispatchLease lease = store.claimNext(owner(), Duration.ofSeconds(1)).orElseThrow();
        AtomicBoolean reachedCallback = new AtomicBoolean();
        assertThatThrownBy(() -> store.guardWrite(lease, () -> {
            reachedCallback.set(true);
            var row = runs.findById(run).orElseThrow();
            row.setError("late write must roll back");
            runs.save(row);
            try {
                Thread.sleep(1100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return null;
        })).isInstanceOf(RunLeaseLostException.class);
        assertThat(reachedCallback).isTrue();
        assertThat(runs.findById(run).orElseThrow().getError()).isNull();
        assertThat(store.renew(lease, TTL)).isFalse();
    }

    @Test
    void enclosingTransactionCannotCommitAfterItsNestedGuardLeaseExpires() {
        UUID session = session();
        UUID run = run(session);
        enqueue(session, run);
        DispatchLease lease = claim();
        assertThatThrownBy(() -> tx(() -> {
            store.guardWrite(lease, () -> {
                var row = runs.findById(run).orElseThrow();
                row.setError("nested write must roll back");
                runs.save(row);
                return null;
            });
            expire(lease);
            return null;
        })).isInstanceOf(RunLeaseLostException.class);
        assertThat(runs.findById(run).orElseThrow().getError()).isNull();
        assertThat(store.renew(lease, TTL)).isTrue();
    }

    @Test
    void expiredRunningWorkIsFailedAtomicallyAndNeverReplayed() {
        UUID session = session();
        UUID run = run(session);
        DispatchTask task = enqueue(session, run);
        DispatchLease lease = claim();
        expire(lease);
        assertThat(store.renew(lease, TTL)).isFalse();
        assertThat(store.complete(lease)).isFalse();
        assertThat(store.claimNext(owner(), TTL)).isEmpty();
        assertThat(store.reapExpired(10, expired -> {
            var row = runs.findById(expired.runId()).orElseThrow();
            row.setStatus(RunStatus.FAILED);
            runs.save(row);
        })).isEqualTo(1);
        assertThat(store.find(task.id()).orElseThrow().status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(runs.findById(run).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(store.claimNext(owner(), TTL)).isEmpty();
    }

    @Test
    void reaperOnlyFinishesTheTaskWhenTheRunWasAlreadySucceededOrSuspended() {
        for (RunStatus status : new RunStatus[]{RunStatus.SUCCEEDED, RunStatus.WAITING_APPROVAL}) {
            UUID session = session();
            UUID run = run(session);
            enqueue(session, run);
            DispatchLease lease = claim();
            tx(() -> {
                var row = runs.findById(run).orElseThrow();
                row.setStatus(status);
                runs.save(row);
                return null;
            });
            expire(lease);
            assertThat(store.reapExpired(10, ignored -> { })).isEqualTo(1);
            assertThat(store.find(lease.taskId()).orElseThrow().status()).isEqualTo(DispatchStatus.DONE);
            assertThat(runs.findById(run).orElseThrow().getStatus()).isEqualTo(status);
        }
    }

    @Test
    void reaperCallbackFailureRollsBackItsRunChangesAndTaskTransition() {
        UUID session = session();
        UUID run = run(session);
        enqueue(session, run);
        DispatchLease lease = claim();
        expire(lease);
        assertThatThrownBy(() -> store.reapExpired(10, expired -> {
            var row = runs.findById(run).orElseThrow();
            row.setStatus(RunStatus.FAILED);
            runs.save(row);
            throw new IllegalStateException("close failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(lease.taskId()).orElseThrow().status()).isEqualTo(DispatchStatus.RUNNING);
        assertThat(runs.findById(run).orElseThrow().getStatus()).isEqualTo(RunStatus.PENDING);
    }

    @Test
    void queuedCancellationClosesRunAndTaskInOneTransaction() {
        UUID session = session();
        UUID run = run(session);
        DispatchTask task = enqueue(session, run);
        assertThat(store.cancelQueued(run, canceled -> {
            var row = runs.findById(canceled.runId()).orElseThrow();
            row.setStatus(RunStatus.CANCELED);
            runs.save(row);
        })).isEqualTo(1);
        assertThat(runs.findById(run).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELED);
        assertThat(store.find(task.id()).orElseThrow().status()).isEqualTo(DispatchStatus.CANCELED);
        assertThat(store.find(task.id()).orElseThrow().cancelRequestedAt()).isNotNull();
        assertThat(store.countOutstanding(session)).isZero();
        assertThat(store.claimNext(owner(), TTL)).isEmpty();
    }

    @Test
    void queuedCancellationFailureDoesNotLoseTheTask() {
        UUID session = session();
        UUID run = run(session);
        DispatchTask task = enqueue(session, run);
        assertThatThrownBy(() -> store.cancelQueued(run, ignored -> {
            var row = runs.findById(run).orElseThrow();
            row.setStatus(RunStatus.CANCELED);
            runs.save(row);
            throw new IllegalStateException("cancel failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(task.id()).orElseThrow().status()).isEqualTo(DispatchStatus.QUEUED);
        assertThat(runs.findById(run).orElseThrow().getStatus()).isEqualTo(RunStatus.PENDING);
    }

    private UUID session() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into sessions (id, user_id, status, created_at, updated_at) "
                + "values (?, ?, 'ACTIVE', ?, ?)", id, UUID.randomUUID(), now(), now());
        return id;
    }

    private UUID run(UUID sessionId) {
        UUID id = UUID.randomUUID();
        insertRun(id, sessionId);
        return id;
    }

    private void insertRun(UUID id, UUID sessionId) {
        jdbc.update("insert into workflow_runs (id, workflow_id, workflow_version, session_id, status, "
                        + "steps, created_at) values (?, ?, 1, ?, 'PENDING', 0, ?)",
                id, UUID.randomUUID(), sessionId, now());
    }

    private DispatchTask enqueue(UUID sessionId, UUID runId) {
        return tx(() -> store.enqueueProcess(sessionId, runId));
    }

    private DispatchLease claim() {
        return store.claimNext(owner(), TTL).orElseThrow();
    }

    private void expire(DispatchLease lease) {
        jdbc.update("update session_dispatch_tasks set lease_until = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), lease.taskId());
    }

    private <T> T tx(Supplier<T> callback) {
        return new TransactionTemplate(transactionManager).execute(status -> callback.get());
    }

    private static String owner() {
        return UUID.randomUUID().toString();
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JdbcConfiguration {
        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
