package com.aiflow.engine.approval.notify;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通知分发：进程内的「单线程 + 有界队列 + 逐渠道隔离」投递器。
 *
 * <p>它**不实现** {@link ApprovalNotifier}，是故意的：它自己要拿 {@code List<ApprovalNotifier>} 来做分发，
 * 若本身也是渠道，构造器注入就变成自引用（Spring 得先造好它才能拿到它要的清单），要靠 {@code @Primary}
 * 或延迟注入绕过去 —— 那种绕法在半年后没人看得懂。接口留给「渠道作者」，装配点留在
 * {@link com.aiflow.engine.approval.ApprovalService}。
 *
 * <p>四个决定：
 * <ol>
 *   <li><b>投递异步</b>：调用方（审批状态机）只做一次入队。webhook 超时是秒级的事，绝不能挂在审批决策
 *       的路径上 —— 审批该多快就多快，通知慢是通知自己的问题。</li>
 *   <li><b>队列满丢最老</b>：近期的状态变化比十秒前那条更值得留（与审计落库同一取舍）。丢了多少计数并暴露在
 *       {@code /api/v1/approvals/notify} 上 —— 静默丢才是真的坏。</li>
 *   <li><b>失败只计数，不重试、不回滚</b>：通知是**尽力而为**的旁路。接收方挂了不该让审批也停摆；
 *       要可靠投递请查审批链（approvals / approval_events 是持久的）并按需重放。</li>
 *   <li><b>按 (approvalId, event, level) 去重</b>：状态机在事务里注册 afterCommit 回调，重放/重试
 *       （比如运维手工再跑一次扫描）不该给接收方灌第二条一样的卡片。窗口是进程内、LRU 式丢弃。</li>
 * </ol>
 *
 * <p>没有可用渠道（或总开关关掉）时连线程都不起：队列是空的，起个线程在那儿空转没有意义。
 */
@Component
public class ApprovalNotifyDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNotifyDispatcher.class);
    private static final long IDLE_WAIT_MS = 500L;
    private static final long SHUTDOWN_JOIN_MS = 2000L;
    private static final long DROP_LOG_EVERY = 100L;
    private static final int ERROR_LIMIT = 300;

    private final ApprovalNotifyProperties properties;
    private final List<ApprovalNotifier> channels;
    private final List<ApprovalNotifier> activeChannels;
    private final ArrayDeque<ApprovalNotification> queue;
    private final Object monitor = new Object();
    private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong deduped = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile String lastError;
    private volatile boolean stopping;
    private volatile Thread worker;

    public ApprovalNotifyDispatcher(List<ApprovalNotifier> channels, ApprovalNotifyProperties properties) {
        this.properties = properties;
        this.channels = channels == null ? List.of() : channels.stream()
                .sorted(Comparator.comparing(ApprovalNotifier::channel))
                .toList();
        this.activeChannels = this.channels.stream().filter(ApprovalNotifier::enabled).toList();
        // 初始容量不跟着配置走：queueSize 配错成 8 位数时不该在启动期就把堆吃掉（上限仍是 queueSize）
        this.queue = new ArrayDeque<>(Math.min(1024, Math.max(16, properties.queueSize())));
        if (properties.enabled() && this.activeChannels.isEmpty()) {
            log.info("审批通知已开启，但当前没有可用渠道（清单: {}）：通知只会进事件总线，不投递",
                    this.channels.stream().map(ApprovalNotifier::channel).toList());
        }
    }

    /**
     * 入队即返回。**调用点在事务的 afterCommit 里**（见 {@code ApprovalService}）：
     * 事务回滚就不该有通知 —— 否则接收方会看到一条「从来没发生过的待办」。
     */
    public void enqueue(ApprovalNotification notification) {
        if (notification == null || !properties.enabled() || activeChannels.isEmpty()) {
            return;
        }
        if (!markNew(notification.dedupeKey())) {
            deduped.incrementAndGet();
            return;
        }
        start();
        int evicted = 0;
        synchronized (monitor) {
            int capacity = Math.max(1, properties.queueSize());
            while (queue.size() >= capacity) {
                queue.pollFirst();
                evicted++;
            }
            queue.addLast(notification);
            monitor.notifyAll();
        }
        enqueued.incrementAndGet();
        if (evicted > 0) {
            long total = dropped.addAndGet(evicted);
            // 只有在「跨过 100 的整数倍」或首次丢弃时打日志：队列满会连续发生，逐条打就是刷屏
            if (total == evicted || total / DROP_LOG_EVERY > (total - evicted) / DROP_LOG_EVERY) {
                log.warn("审批通知队列已满，丢弃最老 {} 条（累计 {} 条）：接收方是不是变慢了？", evicted, total);
            }
        }
    }

    /** 渠道清单 + 队列水位 + 计数，够回答「配了没生效 / 投出去没 / 丢了多少」。 */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.enabled());
        status.put("queueCapacity", Math.max(1, properties.queueSize()));
        synchronized (monitor) {
            status.put("queueDepth", queue.size());
        }
        status.put("activeChannels", activeChannels.stream().map(ApprovalNotifier::channel).toList());
        status.put("enqueued", enqueued.get());
        status.put("delivered", delivered.get());
        status.put("sent", sent.get());
        status.put("failed", failed.get());
        status.put("deduped", deduped.get());
        status.put("dropped", dropped.get());
        status.put("lastError", lastError);
        status.put("channels", channels.stream().map(this::channelStatus).toList());
        return status;
    }

    /** 进程退出前把队列里剩下的尽量投完（最多等 {@value #SHUTDOWN_JOIN_MS} 毫秒）。 */
    @PreDestroy
    void shutdown() {
        Thread thread = worker;
        if (thread == null) {
            return;
        }
        stopping = true;
        synchronized (monitor) {
            monitor.notifyAll();
        }
        try {
            thread.join(SHUTDOWN_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("审批通知投递线程已停止：入队 {} 条，投递 {} 条，去重 {} 条，丢弃 {} 条，失败 {} 次",
                enqueued.get(), delivered.get(), deduped.get(), dropped.get(), failed.get());
    }

    private Map<String, Object> channelStatus(ApprovalNotifier channel) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("channel", channel.channel());
        status.put("enabled", channel.enabled());
        Map<String, Object> own = channel.status();
        if (own != null) {
            own.forEach((key, value) -> status.putIfAbsent(key, value));
        }
        return status;
    }

    /** 去重窗口：命中返回 false；放不下就丢最老的键（LinkedHashMap 的插入序天然是 LRU）。 */
    private boolean markNew(String key) {
        synchronized (seen) {
            if (seen.containsKey(key)) {
                return false;
            }
            seen.put(key, Boolean.TRUE);
            int limit = Math.max(1, properties.dedupeSize());
            while (seen.size() > limit) {
                String oldest = seen.keySet().iterator().next();
                seen.remove(oldest);
            }
            return true;
        }
    }

    private void start() {
        if (worker != null) {
            return;
        }
        synchronized (this) {
            if (worker != null) {
                return;
            }
            Thread thread = Thread.ofPlatform().daemon(true).name("approval-notify").unstarted(this::run);
            worker = thread;
            thread.start();
        }
    }

    private void run() {
        while (true) {
            ApprovalNotification next;
            synchronized (monitor) {
                while (queue.isEmpty() && !stopping) {
                    try {
                        monitor.wait(IDLE_WAIT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (queue.isEmpty()) {
                    return;
                }
                next = queue.pollFirst();
            }
            deliver(next);
            delivered.incrementAndGet();
        }
    }

    private void deliver(ApprovalNotification notification) {
        for (ApprovalNotifier channel : activeChannels) {
            try {
                channel.notify(notification);
                sent.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed.incrementAndGet();
                lastError = channel.channel() + ": 投递被中断";
                return;
            } catch (Exception e) {
                failed.incrementAndGet();
                String message = channel.channel() + ": " + describe(e);
                lastError = message.length() <= ERROR_LIMIT ? message : message.substring(0, ERROR_LIMIT);
                log.warn("审批通知投递失败 channel={} approval={} event={}: {}", channel.channel(),
                        notification.approvalId(), notification.event(), describe(e));
            }
        }
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
