package com.aiflow.engine.tool.audit;

import com.aiflow.engine.config.ToolAuditProperties;
import com.aiflow.engine.persistence.entity.ToolAuditEventEntity;
import com.aiflow.engine.persistence.repo.ToolAuditEventRepository;
import com.aiflow.engine.tool.ToolKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计落库的**异步与降级**语义：批量写、队列满丢最老、写失败只计数、保留期清理、关掉就不写。
 *
 * <p>这些行为单靠端到端测试看不出来（端到端只会看到"记录最终出现在库里"），
 * 而它们恰恰是"审计不能拖慢工具调用"这条承诺的全部内容。
 */
class ToolAuditPersistenceTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final ToolAuditEventRepository repository = mock(ToolAuditEventRepository.class);

    private ToolAuditPersistence persistence(ToolAuditProperties properties) {
        return new ToolAuditPersistence(properties, repository);
    }

    private static ToolAuditProperties props(boolean persist, int queueSize, int retentionDays) {
        return new ToolAuditProperties(true, 1000, persist, queueSize, retentionDays, 360);
    }

    private static ToolAuditEvent event(String toolId) {
        return new ToolAuditEvent(Instant.now(), ToolAuditEvent.SUCCESS, ALICE, null, null, toolId, ToolKind.HTTP,
                "detail", 3L, "abcdef");
    }

    private static List<String> toolIds(List<ToolAuditEventEntity> entities) {
        return entities.stream().map(ToolAuditEventEntity::toEvent).map(ToolAuditEvent::toolId).toList();
    }

    @Test
    void writesQueuedEventsInBatches() throws Exception {
        ToolAuditPersistence persistence = persistence(props(true, 64, 0));

        persistence.accept(event("t1"));
        persistence.accept(event("t2"));
        persistence.accept(event("t3"));

        ArgumentCaptor<List<ToolAuditEventEntity>> captor = ArgumentCaptor.forClass(List.class);
        long deadline = System.currentTimeMillis() + 5_000;
        while (persistence.written() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        verify(repository, org.mockito.Mockito.atLeastOnce()).saveAll(captor.capture());

        assertThat(persistence.written()).isEqualTo(3);
        assertThat(captor.getAllValues().stream().flatMap(List::stream).map(ToolAuditEventEntity::toEvent))
                .extracting(ToolAuditEvent::toolId)
                .containsExactlyInAnyOrder("t1", "t2", "t3");
        persistence.shutdown();
    }

    @Test
    void dropsTheOldestWhenTheQueueIsFull() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.saveAll(anyList())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return invocation.getArgument(0);
        });
        ToolAuditPersistence persistence = persistence(props(true, 4, 0));

        persistence.accept(event("t1"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("写线程已开始处理第一条").isTrue();
        // 队列容量 4：t2..t5 占满，t6 挤掉最老的 t2，t7 再挤掉 t3
        for (String toolId : List.of("t2", "t3", "t4", "t5", "t6", "t7")) {
            persistence.accept(event(toolId));
        }
        assertThat(persistence.dropped()).isEqualTo(2);
        assertThat(persistence.pending()).isEqualTo(4);

        release.countDown();
        long deadline = System.currentTimeMillis() + 5_000;
        while (persistence.written() < 5 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        ArgumentCaptor<List<ToolAuditEventEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).saveAll(captor.capture());
        assertThat(captor.getAllValues().stream().flatMap(List::stream).map(ToolAuditEventEntity::toEvent))
                .extracting(ToolAuditEvent::toolId)
                .as("丢的是最老的，近期记录必须留下")
                .containsExactlyInAnyOrder("t1", "t4", "t5", "t6", "t7");
        persistence.shutdown();
    }

    @Test
    void writeFailureIsCountedAndDoesNotKillTheWriter() throws Exception {
        when(repository.saveAll(anyList())).thenThrow(new IllegalStateException("db down"));
        ToolAuditPersistence persistence = persistence(props(true, 16, 0));

        persistence.accept(event("t1"));
        long deadline = System.currentTimeMillis() + 5_000;
        while (persistence.writeFailures() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(persistence.writeFailures()).isEqualTo(1);
        assertThat(persistence.written()).isZero();

        // 线程还活着：库恢复后下一条照样能写进去。
        // 注意这里必须用 doAnswer().when(...) 而不是 when(...).thenAnswer(...) —— 后者会**先真的调用**
        // 一次 mock，而那次调用按上面的桩是要抛异常的（Mockito 经典坑）。
        org.mockito.Mockito.doAnswer(invocation -> invocation.getArgument(0)).when(repository).saveAll(anyList());
        persistence.accept(event("t2"));
        deadline = System.currentTimeMillis() + 5_000;
        while (persistence.written() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(persistence.written()).isEqualTo(1);
        persistence.shutdown();
    }

    @Test
    void purgeRunsWithRetentionAndIsSkippedWhenRetentionIsDisabled() throws Exception {
        ToolAuditPersistence persistence = persistence(props(true, 16, 7));
        persistence.accept(event("t1"));
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        long deadline = System.currentTimeMillis() + 5_000;
        while (org.mockito.Mockito.mockingDetails(repository).getInvocations().stream()
                .noneMatch(i -> "deleteOlderThan".equals(i.getMethod().getName()))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        verify(repository).deleteOlderThan(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(Instant.now().minus(Duration.ofDays(7)).minusSeconds(30),
                Instant.now().minus(Duration.ofDays(7)).plusSeconds(30));
        persistence.shutdown();

        ToolAuditEventRepository quiet = mock(ToolAuditEventRepository.class);
        ToolAuditPersistence noRetention = new ToolAuditPersistence(props(true, 16, 0), quiet);
        noRetention.accept(event("t2"));
        Thread.sleep(300);
        verify(quiet, never()).deleteOlderThan(any());
        noRetention.shutdown();
    }

    @Test
    void disabledPersistenceWritesNothing() throws Exception {
        ToolAuditPersistence persistence = persistence(props(false, 16, 0));
        persistence.accept(event("t1"));
        Thread.sleep(300);

        assertThat(persistence.enabled()).isFalse();
        assertThat(persistence.pending()).isZero();
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void recentReadsRowsAndCapsTheLimit() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ToolAuditEventEntity.of(event("t1"), Instant.now()))));
        when(repository.count()).thenReturn(42L);
        ToolAuditPersistence persistence = persistence(props(true, 16, 0));

        List<ToolAuditEvent> events = persistence.recent(ALICE, "t1", "success", 10_000);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), page.capture());
        assertThat(page.getValue().getPageSize()).as("单次查询上限 500 条，防止一个接口把库拖垮").isEqualTo(500);
        assertThat(page.getValue().getSort().getOrderFor("at").getDirection().isDescending()).isTrue();
        assertThat(events).extracting(ToolAuditEvent::toolId).containsExactly("t1");
        assertThat(persistence.count()).isEqualTo(42L);
    }

    @Test
    void queryFailurePropagatesSoCallerCanFallBack() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenThrow(new IllegalStateException("db down"));
        ToolAuditPersistence persistence = persistence(props(true, 16, 0));

        assertThatThrownBy(() -> persistence.recent(null, null, null, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }
}
