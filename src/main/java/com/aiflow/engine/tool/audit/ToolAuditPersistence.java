package com.aiflow.engine.tool.audit;

import com.aiflow.engine.config.ToolAuditProperties;
import com.aiflow.engine.persistence.entity.ToolAuditEventEntity;
import com.aiflow.engine.persistence.repo.ToolAuditEventRepository;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计落库：**写库这件事不许拖慢工具调用**。
 *
 * <p>三个设计决定：
 * <ol>
 *   <li>**异步 + 单线程 + 批量**。调用方只做一次入队；写库在自己的线程里 64 条一批地落。
 *       工具调用路径上多的是一次 `offer`，不是一次数据库往返。</li>
 *   <li>**队列满丢最老的，写失败只计数不重试**。审计的价值随记录变老而递减，
 *       「刚发生的那次调用」比「十分钟前那次」更值得留；库抖动时无条件重试只会把队列堵死，
 *       最后丢的更多。丢了多少、失败多少都计数并暴露在查询接口上 —— 静默丢数据才是真的坏。</li>
 *   <li>**内存缓冲与日志照旧**。落库是第三条腿，不是替代品：库不可用时缓冲仍能回答「刚才发生了什么」。</li>
 * </ol>
 *
 * <p>保留期清理也在这个线程里顺带做（没有任何新调度器）：到点执行一次批量删除，
 * 用比较并交换保证只有一个线程真的去删。
 */
@Component
public class ToolAuditPersistence {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditPersistence.class);
    private static final int BATCH_MAX = 64;
    private static final int QUERY_MAX = 500;
    private static final long IDLE_POLL_MS = 500;
    private static final long SHUTDOWN_DRAIN_MS = 2000;
    private static final long FAILURE_LOG_EVERY = 50;
    private static final long DROP_LOG_EVERY = 100;

    private final ToolAuditProperties properties;
    private final ToolAuditEventRepository repository;
    private final BlockingQueue<ToolAuditEvent> queue;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private final AtomicLong lastPurgeAt = new AtomicLong();
    private volatile Thread worker;

    public ToolAuditPersistence(ToolAuditProperties properties, ToolAuditEventRepository repository) {
        this.properties = properties;
        this.repository = repository;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.queueSize()));
    }

    /** 是否真的在落库（总开关关掉或显式关掉落库时为 false，此时查询必须回退内存缓冲）。 */
    public boolean enabled() {
        return properties.enabled() && properties.persist();
    }

    /** 入队即返回。 */
    public void accept(ToolAuditEvent event) {
        if (!enabled() || event == null) {
            return;
        }
        start();
        if (queue.offer(event)) {
            return;
        }
        queue.poll();
        queue.offer(event);
        long total = dropped.incrementAndGet();
        if (total == 1 || total % DROP_LOG_EVERY == 0) {
            log.warn("审计写库队列已满，丢弃最老记录（累计 {} 条）：库是不是变慢了？", total);
        }
    }

    /** 从库里查最近若干条（顺序：时间倒序，同刻按自增 id 倒序）。异常直接抛出，由调用方决定回退。 */
    public List<ToolAuditEvent> recent(UUID userId, String toolId, String outcome, int limit) {
        int capped = Math.max(1, Math.min(limit, QUERY_MAX));
        Sort sort = Sort.by(Sort.Direction.DESC, "at", "id");
        return repository.findAll(specification(userId, toolId, outcome), PageRequest.of(0, capped, sort))
                .stream()
                .map(ToolAuditEventEntity::toEvent)
                .toList();
    }

    public long count() {
        return repository.count();
    }

    public long written() {
        return written.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public long writeFailures() {
        return writeFailures.get();
    }

    public int pending() {
        return queue.size();
    }

    /** 进程退出前把队列里剩下的尽量写完（最多等 {@value #SHUTDOWN_DRAIN_MS} 毫秒）。 */
    @PreDestroy
    void shutdown() {
        Thread thread = worker;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        long deadline = System.currentTimeMillis() + SHUTDOWN_DRAIN_MS;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            List<ToolAuditEvent> batch = new ArrayList<>(BATCH_MAX);
            queue.drainTo(batch, BATCH_MAX);
            save(batch);
        }
        try {
            thread.join(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("审计写入线程已停止：写入 {} 条，丢弃 {} 条，失败 {} 批", written.get(), dropped.get(),
                writeFailures.get());
    }

    private Specification<ToolAuditEventEntity> specification(UUID userId, String toolId, String outcome) {
        String toolFilter = toolId == null || toolId.isBlank() ? null : toolId.strip();
        String outcomeFilter = outcome == null || outcome.isBlank()
                ? null : outcome.strip().toUpperCase(Locale.ROOT);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>(3);
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (toolFilter != null) {
                predicates.add(cb.equal(root.get("toolId"), toolFilter));
            }
            if (outcomeFilter != null) {
                predicates.add(cb.equal(root.get("outcome"), outcomeFilter));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private void start() {
        if (worker != null) {
            return;
        }
        synchronized (this) {
            if (worker != null) {
                return;
            }
            Thread thread = Thread.ofPlatform().daemon(true).name("tool-audit-writer").unstarted(this::run);
            worker = thread;
            thread.start();
        }
    }

    private void run() {
        purgeIfDue(true);
        while (true) {
            try {
                ToolAuditEvent first = queue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    purgeIfDue(false);
                    continue;
                }
                List<ToolAuditEvent> batch = new ArrayList<>(BATCH_MAX);
                batch.add(first);
                queue.drainTo(batch, BATCH_MAX - 1);
                save(batch);
                purgeIfDue(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void save(List<ToolAuditEvent> batch) {
        if (batch.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        try {
            repository.saveAll(batch.stream().map(event -> ToolAuditEventEntity.of(event, now)).toList());
            written.addAndGet(batch.size());
        } catch (RuntimeException e) {
            long failures = writeFailures.incrementAndGet();
            if (failures == 1 || failures % FAILURE_LOG_EVERY == 0) {
                log.warn("审计写库失败（累计 {} 批；本批 {} 条已放弃，内存缓冲与日志不受影响）: {}",
                        failures, batch.size(), e.getMessage());
            }
        }
    }

    private void purgeIfDue(boolean firstRun) {
        int retentionDays = properties.retentionDays();
        if (retentionDays <= 0) {
            return;
        }
        long intervalMs = Math.max(1, properties.purgeIntervalMinutes()) * 60_000L;
        long now = System.currentTimeMillis();
        long last = lastPurgeAt.get();
        if (!firstRun && last != 0 && now - last < intervalMs) {
            return;
        }
        if (!lastPurgeAt.compareAndSet(last, now)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        try {
            int deleted = repository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("审计保留期清理：删除 {} 条早于 {} 的记录（retention-days={}）", deleted, cutoff, retentionDays);
            }
        } catch (RuntimeException e) {
            log.warn("审计保留期清理失败（下次到点再试）: {}", e.getMessage());
        }
    }
}
