package com.aiflow.engine.tool.audit;

import com.aiflow.engine.config.ToolAuditProperties;
import com.aiflow.engine.persistence.entity.ToolAuditEventEntity;
import com.aiflow.engine.persistence.repo.ToolAuditEventRepository;
import com.aiflow.engine.tool.ToolAuthorizer;
import com.aiflow.engine.tool.ToolKind;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 审计缓冲：环形淘汰、过滤、结果口径映射。 */
class ToolAuditServiceTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static ToolAuditService service(int maxEntries) {
        return new ToolAuditService(new ToolAuditProperties(true, maxEntries));
    }

    /**
     * 落库查询失败时**必须回退内存缓冲**，而不是把异常抛给接口：审计查询是排障动作，
     * 库抖动时"少看到几条"远好过"什么都看不到"。
     */
    @Test
    void fallsBackToMemoryBufferWhenPersistentQueryFails() {
        ToolAuditEventRepository repository = mock(ToolAuditEventRepository.class);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenThrow(new IllegalStateException("db down"));
        ToolAuditProperties properties = new ToolAuditProperties(true, 100);
        ToolAuditService service = new ToolAuditService(properties,
                new ToolAuditPersistence(properties, repository));
        record(service, ALICE, "t1", ToolAuditEvent.SUCCESS);

        ToolAuditService.RecentAudit audit = service.recent(null, null, null, 10);

        assertThat(audit.store()).isEqualTo("memory");
        assertThat(audit.events()).extracting(ToolAuditEvent::toolId).containsExactly("t1");
    }

    /** 库可用时以库为准：跨重启追溯靠的就是这一条。 */
    @Test
    void readsFromDatabaseWhenAvailable() {
        ToolAuditEventRepository repository = mock(ToolAuditEventRepository.class);
        ToolAuditEventEntity row = ToolAuditEventEntity.of(
                new ToolAuditEvent(Instant.now(), ToolAuditEvent.DENIED, ALICE, null, null, "t9", ToolKind.MCP,
                        "未授权", 0L, "h"), Instant.now());
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row)));
        when(repository.count()).thenReturn(7L);
        ToolAuditProperties properties = new ToolAuditProperties(true, 100);
        ToolAuditService service = new ToolAuditService(properties,
                new ToolAuditPersistence(properties, repository));

        ToolAuditService.RecentAudit audit = service.recent(ALICE, "t9", "denied", 5);

        assertThat(audit.store()).isEqualTo("database");
        assertThat(audit.storeTotal()).isEqualTo(7L);
        assertThat(audit.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.toolId()).isEqualTo("t9");
                    assertThat(event.kind()).isEqualTo(ToolKind.MCP);
                });
    }

    private static void record(ToolAuditService service, UUID userId, String toolId, String outcome) {
        service.record(service.event(outcome, userId, null, null, toolId, ToolKind.HTTP, "detail", 3L, "abcdef"));
    }

    @Test
    void keepsNewestFirstAndEvictsOldest() {
        ToolAuditService service = service(3);
        record(service, ALICE, "t1", ToolAuditEvent.SUCCESS);
        record(service, ALICE, "t2", ToolAuditEvent.SUCCESS);
        record(service, ALICE, "t3", ToolAuditEvent.SUCCESS);
        record(service, ALICE, "t4", ToolAuditEvent.SUCCESS);

        List<ToolAuditEvent> events = service.recent(null, null, 10);

        assertThat(service.size()).isEqualTo(3);
        assertThat(events).extracting(ToolAuditEvent::toolId).containsExactly("t4", "t3", "t2");
    }

    @Test
    void filtersByUserAndTool() {
        ToolAuditService service = service(10);
        record(service, ALICE, "t1", ToolAuditEvent.SUCCESS);
        record(service, BOB, "t1", ToolAuditEvent.FAILED);
        record(service, ALICE, "t2", ToolAuditEvent.SUCCESS);

        assertThat(service.recent(ALICE, null, 10)).hasSize(2);
        assertThat(service.recent(BOB, null, 10)).extracting(ToolAuditEvent::outcome).containsExactly("FAILED");
        assertThat(service.recent(null, "t2", 10)).hasSize(1);
        assertThat(service.recent(ALICE, "t1", 10)).hasSize(1);
    }

    @Test
    void respectsLimitAndDisabledSwitch() {
        ToolAuditService service = service(10);
        for (int i = 0; i < 5; i++) {
            record(service, ALICE, "t" + i, ToolAuditEvent.SUCCESS);
        }
        assertThat(service.recent(null, null, 2)).hasSize(2);

        ToolAuditService disabled = new ToolAuditService(new ToolAuditProperties(false, 10));
        record(disabled, ALICE, "t1", ToolAuditEvent.SUCCESS);
        assertThat(disabled.size()).isZero();
        assertThat(disabled.recent(null, null, 10)).isEmpty();
    }

    @Test
    void truncatesLongDetail() {
        ToolAuditService service = service(10);
        service.record(service.event(ToolAuditEvent.FAILED, ALICE, null, null, "t1", ToolKind.MCP,
                "x".repeat(1000), 1L, "h"));

        String detail = service.recent(null, null, 1).get(0).detail();

        assertThat(detail).hasSize(241);
        assertThat(detail).endsWith("…");
    }

    @Test
    void mapsOutcomeFromNodeResult() {
        assertThat(ToolAuditService.outcomeOf("SUCCEEDED", false, null)).isEqualTo(ToolAuditEvent.SUCCESS);
        assertThat(ToolAuditService.outcomeOf("SUCCEEDED", true, null)).isEqualTo(ToolAuditEvent.REPLAYED);
        assertThat(ToolAuditService.outcomeOf("FAILED", false, "连接超时")).isEqualTo(ToolAuditEvent.FAILED);
        assertThat(ToolAuditService.outcomeOf("FAILED", false, ToolAuthorizer.DENIED_PREFIX + "该用户不在允许名单里"))
                .isEqualTo(ToolAuditEvent.DENIED);
    }
}
