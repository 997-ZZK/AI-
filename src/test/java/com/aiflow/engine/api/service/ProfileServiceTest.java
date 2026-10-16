package com.aiflow.engine.api.service;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.config.ToolAuditProperties;
import com.aiflow.engine.persistence.entity.UserEntity;
import com.aiflow.engine.persistence.entity.UserProfileEntity;
import com.aiflow.engine.persistence.repo.ProfileStatisticsRepository;
import com.aiflow.engine.persistence.repo.UserProfileRepository;
import com.aiflow.engine.persistence.repo.UserRepository;
import com.aiflow.engine.tool.audit.ToolAuditService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProfileServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-09T06:00:00Z");
    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProfileStatisticsRepository statistics = mock(ProfileStatisticsRepository.class);
    private final ToolAuditService audits = mock(ToolAuditService.class);
    private final ToolAuditProperties properties = new ToolAuditProperties(true, 100, true, 100, 7, 60);

    private ProfileService service(Instant at) {
        return new ProfileService(profiles, users, statistics, audits, properties, Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test
    void defaultAndResetReadsNeverRecreateHistoryOrState() {
        when(users.existsById(userId)).thenReturn(true);
        when(profiles.findById(userId)).thenReturn(Optional.empty());
        var absent = service(now).get(userId);
        assertThat(absent.aggregationEnabled()).isFalse();
        assertThat(absent.version()).isZero();
        assertThat(absent.statisticsSince()).isNull();
        assertThat(absent.statistics().runCount()).isZero();
        verify(profiles, never()).save(any());
        verifyNoInteractions(statistics);

        UserProfileEntity reset = new UserProfileEntity(userId, now.minusSeconds(5));
        reset.reset(now);
        when(profiles.findById(userId)).thenReturn(Optional.of(reset));
        var after = service(now.plusSeconds(10)).get(userId);
        assertThat(after.version()).isEqualTo(1);
        assertThat(after.statisticsSince()).isEqualTo(now);
        assertThat(after.statistics().runCount()).isZero();
        verifyNoInteractions(statistics);
    }

    @Test
    void currentWindowUsesLaterFloorAndSkipsToolsWhenPersistenceOff() {
        when(users.existsById(userId)).thenReturn(true);
        UserProfileEntity profile = new UserProfileEntity(userId, now.minus(Duration.ofDays(50)));
        profile.update(true, "Asia/Shanghai", now.minus(Duration.ofDays(40)));
        when(profiles.findById(userId)).thenReturn(Optional.of(profile));
        Instant from = now.minus(Duration.ofDays(30));
        when(statistics.runs(userId, from, now)).thenReturn(List.of());
        when(statistics.nodes(userId, from, now)).thenReturn(List.of());
        var view = service(now).get(userId);
        assertThat(view.windowFrom()).isEqualTo(from);
        assertThat(view.toolCoverage().retentionDays()).isEqualTo(7);
        assertThat(view.toolCoverage().persistent()).isFalse();
        verify(statistics, never()).tools(any(), any(), any());
    }

    @Test
    void enabledTimezoneUpdateKeepsFloorAndResetThenReenableAdvancesIt() {
        when(users.findByIdForProfileUpdate(userId)).thenReturn(Optional.of(new UserEntity(userId, "synthetic", null, now)));
        UserProfileEntity profile = new UserProfileEntity(userId, now.minus(Duration.ofDays(2)));
        Instant first = now.minus(Duration.ofDays(1));
        profile.update(true, "UTC", first);
        when(profiles.findById(userId)).thenReturn(Optional.of(profile));
        when(statistics.runs(any(), any(), any())).thenReturn(List.of());
        when(statistics.nodes(any(), any(), any())).thenReturn(List.of());
        var updated = service(now).update(userId, true, "Asia/Shanghai", 1);
        assertThat(updated.statisticsSince()).isEqualTo(first);
        assertThat(updated.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(updated.version()).isEqualTo(2);

        Instant resetTime = now.plusSeconds(10);
        var reset = service(resetTime).reset(userId, 2);
        assertThat(reset.aggregationEnabled()).isFalse();
        assertThat(reset.statisticsSince()).isEqualTo(resetTime);
        assertThat(reset.version()).isEqualTo(3);
        Instant reenable = now.plusSeconds(20);
        var enabled = service(reenable).update(userId, true, "UTC", 3);
        assertThat(enabled.statisticsSince()).isEqualTo(reenable);
        assertThat(enabled.windowFrom()).isEqualTo(reenable);
        assertThat(enabled.version()).isEqualTo(4);
    }

    @Test
    void staleVersionCannotUndoResetOrCreateControlRow() {
        when(users.findByIdForProfileUpdate(userId)).thenReturn(Optional.of(new UserEntity(userId, "synthetic", null, now)));
        UserProfileEntity profile = new UserProfileEntity(userId, now);
        profile.reset(now);
        when(profiles.findById(userId)).thenReturn(Optional.of(profile));
        assertThatThrownBy(() -> service(now).update(userId, true, "UTC", 0))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(profile.isAggregationEnabled()).isFalse();
        assertThat(profile.getVersion()).isEqualTo(1);
        verify(profiles, never()).save(any());
        verifyNoInteractions(statistics);
    }

    @Test
    void invalidZoneAndVersionsAreClientErrorsAndUnknownUsersAreNotFound() {
        for (String zone : new String[]{"", "not-a-time-zone", "a".repeat(65)}) {
            assertThatThrownBy(() -> service(now).update(userId, true, zone, 0))
                    .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
        assertThatThrownBy(() -> service(now).reset(userId, -1))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service(now).get(userId))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(profiles, statistics);
    }
}
