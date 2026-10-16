package com.aiflow.engine.approval.notify;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分发器的六条语义：按渠道分发、渠道互相隔离、队列满丢最老、去重、总开关、没有渠道就不起线程。
 *
 * <p>不起 Spring：这些是纯内存行为，起上下文只会把「分发器的语义」和「装配有没有写对」两件事混在一起
 * （装配由 {@code ApprovalNotifyIntegrationTest} 盯着）。时间相关的用例一律用闩锁而不是 sleep ——
 * 靠 sleep 的空窗期去制造「工作线程正好卡住」的状态，迟早会在慢机器上闪断。
 */
class ApprovalNotifyDispatcherTest {

    @Test
    void fansOutToEveryActiveChannel() {
        RecordingChannel log = new RecordingChannel("log");
        RecordingChannel webhook = new RecordingChannel("webhook");
        ApprovalNotifyDispatcher dispatcher = new ApprovalNotifyDispatcher(List.of(webhook, log),
                properties(true, 8, 8));

        ApprovalNotification notification = notification();
        dispatcher.enqueue(notification);

        assertThat(awaitCount(log, 1)).isTrue();
        assertThat(awaitCount(webhook, 1)).isTrue();
        assertThat(log.received).containsExactly(notification.dedupeKey());
        assertThat(dispatcher.status())
                .containsEntry("sent", 2L)
                .containsEntry("delivered", 1L)
                .containsEntry("failed", 0L)
                .containsEntry("dropped", 0L)
                .containsEntry("lastError", null)
                .containsEntry("activeChannels", List.of("log", "webhook"));
        dispatcher.shutdown();
    }

    @Test
    void oneBrokenChannelDoesNotStopTheOthers() {
        RecordingChannel broken = new RecordingChannel("broken");
        broken.failure = new IllegalStateException("connection refused");
        RecordingChannel healthy = new RecordingChannel("healthy");
        ApprovalNotifyDispatcher dispatcher = new ApprovalNotifyDispatcher(List.of(broken, healthy),
                properties(true, 8, 8));

        dispatcher.enqueue(notification());

        assertThat(awaitCount(healthy, 1)).as("一个渠道挂了，另一个必须照常收到").isTrue();
        assertThat(awaitFailure(dispatcher)).isTrue();
        assertThat(dispatcher.status())
                .containsEntry("sent", 1L)
                .containsEntry("failed", 1L)
                .containsEntry("delivered", 1L);
        assertThat((String) dispatcher.status().get("lastError")).contains("broken", "connection refused");
        dispatcher.shutdown();
    }

    @Test
    void fullQueueDropsTheOldestAndKeepsCounting() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        RecordingChannel slow = new RecordingChannel("slow");
        slow.gate = gate;
        ApprovalNotifyDispatcher dispatcher = new ApprovalNotifyDispatcher(List.of(slow), properties(true, 2, 16));

        ApprovalNotification first = notification();
        dispatcher.enqueue(first);
        assertThat(slow.entered.await(3, TimeUnit.SECONDS)).as("工作线程要先拿走第一条并卡住").isTrue();

        ApprovalNotification second = notification();
        ApprovalNotification third = notification();
        ApprovalNotification fourth = notification();
        dispatcher.enqueue(second);
        dispatcher.enqueue(third);
        dispatcher.enqueue(fourth);

        assertThat(dispatcher.status())
                .containsEntry("enqueued", 4L)
                .containsEntry("dropped", 1L)
                .containsEntry("queueDepth", 2);

        gate.countDown();
        assertThat(awaitCount(slow, 3)).isTrue();
        assertThat(slow.received).containsExactly(first.dedupeKey(), third.dedupeKey(), fourth.dedupeKey());
        assertThat(slow.received).as("丢的必须是最老的那条").doesNotContain(second.dedupeKey());
        dispatcher.shutdown();
    }

    @Test
    void sameStateChangeIsDeliveredOnlyOnce() {
        RecordingChannel log = new RecordingChannel("log");
        ApprovalNotifyDispatcher dispatcher = new ApprovalNotifyDispatcher(List.of(log), properties(true, 8, 8));

        ApprovalNotification notification = notification();
        dispatcher.enqueue(notification);
        dispatcher.enqueue(notification);
        dispatcher.enqueue(notification);

        assertThat(awaitCount(log, 1)).isTrue();
        assertThat(dispatcher.status())
                .containsEntry("enqueued", 1L)
                .containsEntry("deduped", 2L)
                .containsEntry("sent", 1L);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        assertThat(log.received).hasSize(1);
        dispatcher.shutdown();
    }

    @Test
    void masterSwitchOffMeansNotEvenQueued() {
        RecordingChannel log = new RecordingChannel("log");
        ApprovalNotifyDispatcher dispatcher = new ApprovalNotifyDispatcher(List.of(log), properties(false, 8, 8));

        dispatcher.enqueue(notification());

        assertThat(dispatcher.status())
                .containsEntry("enabled", false)
                .containsEntry("enqueued", 0L)
                .containsEntry("queueDepth", 0);
        assertThat(log.received).isEmpty();
        assertThat(dispatcher.status().get("channels")).as("关掉也要能被看见，否则「配了没生效」查不出来")
                .isEqualTo(List.of(Map.of("channel", "log", "enabled", true)));
    }

    @Test
    void noChannelMeansNoQueueAndNoThread() {
        ApprovalNotifyDispatcher dispatcher = new ApprovalNotifyDispatcher(List.of(), properties(true, 8, 8));

        dispatcher.enqueue(notification());

        assertThat(dispatcher.status())
                .containsEntry("enqueued", 0L)
                .containsEntry("activeChannels", List.of())
                .containsEntry("queueDepth", 0);
        assertThat(dispatcher.status().get("channels")).isEqualTo(List.of());
    }

    private static ApprovalNotifyProperties properties(boolean enabled, int queueSize, int dedupeSize) {
        return new ApprovalNotifyProperties(enabled, queueSize, dedupeSize,
                new ApprovalNotifyProperties.Webhook(false, "", 3000, ""));
    }

    private static ApprovalNotification notification() {
        return notification(UUID.randomUUID(), "REQUESTED", 1);
    }

    private static ApprovalNotification notification(UUID approvalId, String event, int level) {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        return new ApprovalNotification(event, approvalId, UUID.randomUUID(), "approve", level, null, "PENDING",
                "标题", "详情", List.of("alice"), at.plusSeconds(600), null, null, null, at);
    }

    private static boolean awaitCount(RecordingChannel channel, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (channel.received.size() < expected && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        return channel.received.size() >= expected;
    }

    private static boolean awaitFailure(ApprovalNotifyDispatcher dispatcher) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Long.valueOf(1L).equals(dispatcher.status().get("failed"))) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        return false;
    }

    private static final class RecordingChannel implements ApprovalNotifier {

        private final String name;
        private final ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch gate;
        private volatile RuntimeException failure;

        private RecordingChannel(String name) {
            this.name = name;
        }

        @Override
        public String channel() {
            return name;
        }

        @Override
        public void notify(ApprovalNotification notification) throws Exception {
            entered.countDown();
            CountDownLatch local = gate;
            if (local != null) {
                local.await(5, TimeUnit.SECONDS);
            }
            RuntimeException broken = failure;
            if (broken != null) {
                throw broken;
            }
            received.add(notification.dedupeKey());
        }
    }
}
