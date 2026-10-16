package com.aiflow.engine.persistence.dispatch;

import com.aiflow.engine.run.RunLeaseLostException;
import com.aiflow.engine.session.DispatchKind;
import com.aiflow.engine.session.DispatchLease;
import com.aiflow.engine.session.DispatchStatus;
import com.aiflow.engine.session.DispatchTask;
import com.aiflow.engine.session.DispatchTaskStore;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** PostgreSQL is the queue and write-fence authority. H2 exists only for contract tests. */
@Service
public class PgDispatchTaskStore implements DispatchTaskStore {
    private static final String UNFINISHED = "('QUEUED', 'RUNNING')";
    private static final String SELECT_CLAIM = """
            select t.* from session_dispatch_tasks t
             where t.status = 'QUEUED'
               and not exists (
                   select 1 from session_dispatch_tasks earlier
                    where earlier.session_id = t.session_id and earlier.seq < t.seq
                      and earlier.status in ('QUEUED', 'RUNNING'))
             order by t.seq limit 1 for update skip locked
            """;
    private static final RowMapper<DispatchTask> TASK_MAPPER = (row, ignored) -> new DispatchTask(
            row.getObject("id", UUID.class), row.getObject("session_id", UUID.class),
            row.getObject("run_id", UUID.class), row.getLong("seq"),
            DispatchKind.valueOf(row.getString("kind")), row.getObject("checkpoint_id", UUID.class),
            row.getObject("approval_id", UUID.class), row.getString("dedupe_key"),
            DispatchStatus.valueOf(row.getString("status")), row.getString("owner_token"),
            instant(row, "lease_until"), instant(row, "cancel_requested_at"),
            instant(row, "created_at"), instant(row, "updated_at"), instant(row, "started_at"),
            instant(row, "finished_at"), row.getString("last_error"));

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final String databaseClock;

    public PgDispatchTaskStore(JdbcTemplate jdbc, EntityManager entityManager,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(transactionManager);
        String database = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        if ("PostgreSQL".equals(database)) {
            databaseClock = "clock_timestamp()";
        } else if ("H2".equals(database)) {
            // H2 PostgreSQL mode freezes CURRENT_TIMESTAMP too. A test-only database function
            // preserves elapsed-time assertions; production always uses PostgreSQL's own clock.
            jdbc.execute("create alias if not exists dispatch_test_clock for "
                    + "\"com.aiflow.engine.persistence.dispatch.PgDispatchTaskStore.contractTestClock\"");
            databaseClock = "dispatch_test_clock()";
        } else {
            throw new IllegalStateException("Dispatch store requires PostgreSQL (or H2 contract tests)");
        }
    }

    @Override
    public void lockSession(UUID sessionId) {
        requireTransaction();
        entityManager.flush();
        List<UUID> rows = jdbc.query("select id from sessions where id = ? for update",
                (row, index) -> row.getObject(1, UUID.class), Objects.requireNonNull(sessionId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Dispatch session not found");
        }
    }

    @Override
    public void lockRunTasks(UUID runId) {
        requireTransaction();
        jdbc.query("select id from session_dispatch_tasks where run_id = ? and status in "
                        + UNFINISHED + " order by seq for update",
                (row, ignored) -> row.getObject(1, UUID.class), Objects.requireNonNull(runId, "runId"));
    }

    @Override
    public DispatchTask enqueueProcess(UUID sessionId, UUID runId) {
        return enqueue(sessionId, runId, DispatchKind.PROCESS, null, null, "PROCESS:" + runId);
    }

    @Override
    public DispatchTask enqueueResume(UUID sessionId, UUID runId, UUID checkpointId, UUID approvalId) {
        Objects.requireNonNull(checkpointId, "checkpointId");
        Objects.requireNonNull(approvalId, "approvalId");
        return enqueue(sessionId, runId, DispatchKind.RESUME, checkpointId, approvalId,
                "RESUME:" + checkpointId);
    }

    private DispatchTask enqueue(UUID sessionId, UUID runId, DispatchKind kind, UUID checkpointId,
                                 UUID approvalId, String dedupeKey) {
        requireTransaction();
        Objects.requireNonNull(runId, "runId");
        lockSession(sessionId);
        // The session row serializes duplicate admission without aborting a PostgreSQL transaction
        // on a unique violation; both JPA facts and this insert share its commit.
        List<DispatchTask> existing = jdbc.query(
                "select * from session_dispatch_tasks where dedupe_key = ?", TASK_MAPPER, dedupeKey);
        if (!existing.isEmpty()) {
            DispatchTask task = existing.getFirst();
            if (!task.sessionId().equals(sessionId) || !task.runId().equals(runId)) {
                throw new IllegalArgumentException("Dispatch dedupe key belongs to another run");
            }
            return task;
        }
        UUID id = UUID.randomUUID();
        Instant now = databaseNow();
        jdbc.update("""
                insert into session_dispatch_tasks
                    (id, session_id, run_id, kind, checkpoint_id, approval_id, dedupe_key, status,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                """, id, sessionId, runId, kind.name(), checkpointId, approvalId, dedupeKey,
                timestamp(now), timestamp(now));
        return find(id).orElseThrow();
    }

    @Override
    public long countOutstanding(UUID sessionId) {
        Long count = jdbc.queryForObject("select count(*) from session_dispatch_tasks "
                + "where session_id = ? and status in " + UNFINISHED, Long.class, sessionId);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<DispatchLease> claimNext(String ownerToken, Duration ttl) {
        validateOwner(ownerToken);
        validateTtl(ttl);
        return transactions.execute(transaction -> {
            List<DispatchTask> tasks = jdbc.query(SELECT_CLAIM, TASK_MAPPER);
            if (tasks.isEmpty()) {
                return Optional.empty();
            }
            DispatchTask task = tasks.getFirst();
            Instant now = databaseNow();
            jdbc.update("""
                    update session_dispatch_tasks set status = 'RUNNING', owner_token = ?,
                        lease_until = ?, started_at = ?, updated_at = ? where id = ? and status = 'QUEUED'
                    """, ownerToken, timestamp(now.plus(ttl)), timestamp(now), timestamp(now), task.id());
            return Optional.of(new DispatchLease(find(task.id()).orElseThrow(), ownerToken));
        });
    }

    @Override
    public Optional<DispatchTask> find(UUID taskId) {
        return jdbc.query("select * from session_dispatch_tasks where id = ?", TASK_MAPPER, taskId)
                .stream().findFirst();
    }

    @Override
    public List<DispatchTask> findByRun(UUID runId) {
        return jdbc.query("select * from session_dispatch_tasks where run_id = ? order by seq",
                TASK_MAPPER, runId);
    }

    @Override
    public boolean renew(DispatchLease lease, Duration ttl) {
        validateTtl(ttl);
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            DispatchTask task = lockTask(lease.taskId()).orElse(null);
            Instant now = databaseNow();
            if (!owned(task, lease, now)) {
                return false;
            }
            return jdbc.update("update session_dispatch_tasks set lease_until = ?, updated_at = ? "
                            + "where id = ? and status = 'RUNNING' and owner_token = ?",
                    timestamp(now.plus(ttl)), timestamp(now), lease.taskId(), lease.ownerToken()) == 1;
        }));
    }

    @Override
    public boolean complete(DispatchLease lease) {
        return finish(lease, DispatchStatus.DONE, null);
    }

    @Override
    public boolean fail(DispatchLease lease, String error) {
        return finish(lease, DispatchStatus.FAILED, truncate(error));
    }

    private boolean finish(DispatchLease lease, DispatchStatus status, String error) {
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            DispatchTask task = lockTask(lease.taskId()).orElse(null);
            Instant now = databaseNow();
            if (!owned(task, lease, now)) {
                return false;
            }
            return jdbc.update("""
                    update session_dispatch_tasks set status = ?, finished_at = ?, updated_at = ?,
                        last_error = ? where id = ? and status = 'RUNNING' and owner_token = ?
                    """, status.name(), timestamp(now), timestamp(now), error, lease.taskId(),
                    lease.ownerToken()) == 1;
        }));
    }

    @Override
    public boolean requeueBeforeExecution(DispatchLease lease) {
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            DispatchTask task = lockTask(lease.taskId()).orElse(null);
            Instant now = databaseNow();
            if (!owned(task, lease, now)) {
                return false;
            }
            return jdbc.update("""
                    update session_dispatch_tasks set status = 'QUEUED', owner_token = null,
                        lease_until = null, started_at = null, updated_at = ?
                    where id = ? and status = 'RUNNING' and owner_token = ?
                    """, timestamp(now), lease.taskId(), lease.ownerToken()) == 1;
        }));
    }

    @Override
    public List<DispatchTask> requestCancel(UUID runId) {
        return transactions.execute(transaction -> {
            List<DispatchTask> tasks = jdbc.query("select * from session_dispatch_tasks where run_id = ? "
                    + "and status in " + UNFINISHED + " order by seq for update", TASK_MAPPER, runId);
            Instant now = databaseNow();
            for (DispatchTask task : tasks) {
                jdbc.update("update session_dispatch_tasks set cancel_requested_at = "
                                + "coalesce(cancel_requested_at, ?), updated_at = ? where id = ?",
                        timestamp(now), timestamp(now), task.id());
            }
            return tasks.stream().map(task -> find(task.id()).orElseThrow()).toList();
        });
    }

    @Override
    public int cancelQueued(UUID runId, Consumer<DispatchTask> onCanceled) {
        Objects.requireNonNull(onCanceled, "onCanceled");
        Integer count = transactions.execute(transaction -> {
            List<DispatchTask> tasks = jdbc.query("select * from session_dispatch_tasks "
                    + "where run_id = ? and status = 'QUEUED' order by seq for update", TASK_MAPPER, runId);
            for (DispatchTask task : tasks) {
                onCanceled.accept(task);
                entityManager.flush();
                Instant now = databaseNow();
                jdbc.update("""
                        update session_dispatch_tasks set status = 'CANCELED', cancel_requested_at =
                            coalesce(cancel_requested_at, ?), finished_at = ?, updated_at = ? where id = ?
                        """, timestamp(now), timestamp(now), timestamp(now), task.id());
            }
            return tasks.size();
        });
        return count == null ? 0 : count;
    }

    @Override
    public <T> T guardWrite(DispatchLease lease, Supplier<T> callback) {
        Objects.requireNonNull(callback, "callback");
        return transactions.execute(transaction -> {
            verifyOwned(lockTask(lease.taskId()).orElse(null), lease, databaseNow());
            // REQUIRED may join a @Transactional recorder/approval method. Check again at the
            // actual enclosing commit, not merely when this nested callback returns.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    entityManager.flush();
                    verifyFinalOwnership(lease);
                }
            });
            T result = callback.get();
            // Flush in the fence transaction: deferred JPA writes must not escape ownership checking.
            entityManager.flush();
            verifyFinalOwnership(lease);
            return result;
        });
    }

    @Override
    public int reapExpired(int batchSize, Consumer<DispatchTask> onExpired) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        Objects.requireNonNull(onExpired, "onExpired");
        Integer count = transactions.execute(transaction -> {
            List<DispatchTask> tasks = jdbc.query("select * from session_dispatch_tasks "
                    + "where status = 'RUNNING' and lease_until <= " + databaseClock
                    + " order by seq limit ? for update skip locked", TASK_MAPPER, batchSize);
            int reaped = 0;
            for (DispatchTask task : tasks) {
                Instant now = databaseNow();
                if (task.leaseUntil().isAfter(now)) {
                    continue;
                }
                onExpired.accept(task);
                entityManager.flush();
                now = databaseNow();
                String runStatus = jdbc.queryForObject("select status from workflow_runs where id = ?",
                        String.class, task.runId());
                boolean completedSegment = "SUCCEEDED".equals(runStatus) || "DEGRADED".equals(runStatus)
                        || "CANCELED".equals(runStatus) || "WAITING_APPROVAL".equals(runStatus);
                DispatchStatus taskStatus = completedSegment ? DispatchStatus.DONE : DispatchStatus.FAILED;
                jdbc.update("""
                        update session_dispatch_tasks set status = ?, finished_at = ?,
                            updated_at = ?, last_error = ? where id = ? and status = 'RUNNING'
                        """, taskStatus.name(), timestamp(now), timestamp(now), completedSegment ? null
                                : "Dispatch lease expired; execution outcome uncertain",
                        task.id());
                reaped++;
            }
            return reaped;
        });
        return count == null ? 0 : count;
    }

    private Optional<DispatchTask> lockTask(UUID taskId) {
        return jdbc.query("select * from session_dispatch_tasks where id = ? for update",
                TASK_MAPPER, taskId).stream().findFirst();
    }

    private static boolean owned(DispatchTask task, DispatchLease lease, Instant now) {
        return task != null && task.status() == DispatchStatus.RUNNING
                && lease.ownerToken().equals(task.ownerToken())
                && task.leaseUntil() != null && task.leaseUntil().isAfter(now);
    }

    private static void verifyOwned(DispatchTask task, DispatchLease lease, Instant now) {
        if (!owned(task, lease, now)) {
            throw new RunLeaseLostException("Dispatch lease is no longer owned: " + lease.taskId());
        }
    }

    private void verifyFinalOwnership(DispatchLease lease) {
        DispatchTask after = find(lease.taskId()).orElse(null);
        Instant now = databaseNow();
        // The callback may atomically finish this segment together with the run/message facts.
        // Admission still requires RUNNING; terminal status is permitted only after that entry.
        if (after == null || !lease.ownerToken().equals(after.ownerToken())
                || after.leaseUntil() == null || !after.leaseUntil().isAfter(now)
                || (after.status() != DispatchStatus.RUNNING && !after.status().terminal())) {
            throw new RunLeaseLostException("Dispatch lease is no longer owned: " + lease.taskId());
        }
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("select " + databaseClock,
                (row, ignored) -> instant(row, 1)));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet row, int column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    public static Timestamp contractTestClock() {
        return Timestamp.from(Instant.now());
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Dispatch admission requires the caller's transaction");
        }
    }

    private static void validateOwner(String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank() || ownerToken.length() > 36) {
            throw new IllegalArgumentException("ownerToken must contain 1 to 36 characters");
        }
    }

    private static void validateTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    private static String truncate(String error) {
        return error == null || error.length() <= 4000 ? error : error.substring(0, 4000);
    }
}
