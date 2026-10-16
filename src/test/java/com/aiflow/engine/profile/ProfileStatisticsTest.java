package com.aiflow.engine.profile;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileStatisticsTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    void distinctRunIdsCountOnceAndWaitingIsNotSuccess() {
        UUID workflow = UUID.randomUUID();
        var waiting = new ProfileStatistics.RunSample(UUID.randomUUID(), workflow, "approval", 2,
                "WAITING_APPROVAL", FROM);
        var success = new ProfileStatistics.RunSample(UUID.randomUUID(), workflow, "approval", 2,
                "SUCCEEDED", TO);
        var old = new ProfileStatistics.RunSample(UUID.randomUUID(), workflow, "approval", 1,
                "SUCCEEDED", FROM.minusSeconds(1));
        var future = new ProfileStatistics.RunSample(UUID.randomUUID(), workflow, "approval", 3,
                "PENDING", TO.plusSeconds(1));
        var statistics = ProfileStatistics.aggregate(List.of(waiting, waiting, success, old, future), List.of(),
                List.of(), FROM, TO, ZoneId.of("UTC"));
        assertThat(statistics.runCount()).isEqualTo(2);
        assertThat(statistics.statuses()).containsEntry("SUCCEEDED", 1L).containsEntry("WAITING_APPROVAL", 1L);
        assertThat(statistics.workflows()).containsExactly(new ProfileStatistics.WorkflowCount(workflow, "approval", 2, 2));
    }

    @Test
    void localHoursUseConfiguredZoneIncludingDstRepeatedHour() {
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = from.plusSeconds(86400);
        UUID workflow = UUID.randomUUID();
        var one = new ProfileStatistics.RunSample(UUID.randomUUID(), workflow, "chat", 1, "SUCCEEDED",
                Instant.parse("2026-11-01T05:30:00Z"));
        var two = new ProfileStatistics.RunSample(UUID.randomUUID(), workflow, "chat", 1, "SUCCEEDED",
                Instant.parse("2026-11-01T06:30:00Z"));
        var stats = ProfileStatistics.aggregate(List.of(one, two), List.of(), List.of(), from, to,
                ZoneId.of("America/New_York"));
        assertThat(stats.hours()).hasSize(24);
        assertThat(stats.hours().get(1)).isEqualTo(2);
        assertThat(stats.hours().get(5)).isZero();
    }

    @Test
    void tokenScopeSeparatesUnknownRecordsAndProviderFromRecordedZero() {
        var stats = ProfileStatistics.aggregate(List.of(), List.of(
                new ProfileStatistics.NodeSample("llm.chat", FROM, 10L, "mock"),
                new ProfileStatistics.NodeSample("llm.vision", FROM, 20L, "qwen"),
                new ProfileStatistics.NodeSample("llm.chat", FROM, 5L, null),
                new ProfileStatistics.NodeSample("llm.chat", FROM, 0L, "mock"),
                new ProfileStatistics.NodeSample("llm.chat", FROM, null, "qwen"),
                new ProfileStatistics.NodeSample("llm.vision", FROM, -1L, "mock"),
                new ProfileStatistics.NodeSample("memory.summary", FROM, 1000L, "qwen"),
                new ProfileStatistics.NodeSample("llm.chat", FROM.minusSeconds(1), 1000L, "qwen")),
                List.of(), FROM, TO, ZoneId.of("UTC"));
        assertThat(stats.tokens()).isEqualTo(new ProfileStatistics.TokenCount(10, 20, 5, 2, 4));
    }

    @Test
    void replayAndDeniedRemainDistinctFromSuccessfulCalls() {
        var stats = ProfileStatistics.aggregate(List.of(), List.of(), List.of(
                new ProfileStatistics.ToolSample("skill.read", "SKILL", "SUCCESS", 2),
                new ProfileStatistics.ToolSample("skill.read", "SKILL", "REPLAYED", 3),
                new ProfileStatistics.ToolSample("skill.read", "SKILL", "DENIED", 1),
                new ProfileStatistics.ToolSample("skill.read", "SKILL", "FAILED", 4),
                new ProfileStatistics.ToolSample("skill.read", "SKILL", "OTHER", 1)), FROM, TO, ZoneId.of("UTC"));
        assertThat(stats.tools()).containsExactly(new ProfileStatistics.ToolCount("skill.read", "SKILL", 2, 3, 4, 1, 1));
    }
}
