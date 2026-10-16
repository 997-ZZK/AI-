package com.aiflow.engine.api.service;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.config.ToolAuditProperties;
import com.aiflow.engine.persistence.entity.UserProfileEntity;
import com.aiflow.engine.persistence.repo.ProfileStatisticsRepository;
import com.aiflow.engine.persistence.repo.UserProfileRepository;
import com.aiflow.engine.persistence.repo.UserRepository;
import com.aiflow.engine.profile.ProfileStatistics;
import com.aiflow.engine.tool.audit.ToolAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Profile lifecycle and read-time aggregation. No cache or automatic row creation on GET. */
@Service
public class ProfileService {

    private final UserProfileRepository profiles;
    private final UserRepository users;
    private final ProfileStatisticsRepository statistics;
    private final ToolAuditService auditService;
    private final ToolAuditProperties auditProperties;
    private final Clock clock;

    @Autowired
    public ProfileService(UserProfileRepository profiles, UserRepository users, ProfileStatisticsRepository statistics,
                          ToolAuditService auditService, ToolAuditProperties auditProperties) {
        this(profiles, users, statistics, auditService, auditProperties, Clock.systemUTC());
    }

    ProfileService(UserProfileRepository profiles, UserRepository users, ProfileStatisticsRepository statistics,
                   ToolAuditService auditService, ToolAuditProperties auditProperties, Clock clock) {
        this.profiles = profiles;
        this.users = users;
        this.statistics = statistics;
        this.auditService = auditService;
        this.auditProperties = auditProperties;
        this.clock = clock;
    }

    public record View(UUID userId, boolean aggregationEnabled, Instant statisticsSince, String timezone, long version,
                       Instant createdAt, Instant updatedAt, Instant windowFrom, Instant windowTo,
                       ProfileStatistics statistics, ToolCoverage toolCoverage) {
    }

    public record ToolCoverage(boolean auditEnabled, boolean persistent, int retentionDays, int pendingWrites,
                               long droppedWrites, long failedWriteBatches, String counterScope,
                               boolean mayBeIncomplete, String reason) {
    }

    @Transactional(readOnly = true)
    public View get(UUID userId) {
        requireUser(userId);
        return view(userId, profiles.findById(userId).orElse(null), clock.instant());
    }

    @Transactional
    public View update(UUID userId, boolean enabled, String timezone, long expectedVersion) {
        validateVersion(expectedVersion);
        ZoneId zone = zone(timezone);
        lockUser(userId);
        Instant now = clock.instant();
        UserProfileEntity profile = profiles.findById(userId).orElseGet(() -> new UserProfileEntity(userId, now));
        compareVersion(profile, expectedVersion);
        profile.update(enabled, zone.getId(), now);
        profiles.save(profile);
        return view(userId, profile, now);
    }

    @Transactional
    public View reset(UUID userId, long expectedVersion) {
        validateVersion(expectedVersion);
        lockUser(userId);
        Instant now = clock.instant();
        UserProfileEntity profile = profiles.findById(userId).orElseGet(() -> new UserProfileEntity(userId, now));
        compareVersion(profile, expectedVersion);
        profile.reset(now);
        profiles.save(profile);
        return view(userId, profile, now);
    }

    private View view(UUID userId, UserProfileEntity profile, Instant now) {
        if (profile == null) {
            return new View(userId, false, null, "UTC", 0, null, null, now, now,
                    ProfileStatistics.empty(), coverage(false));
        }
        Instant from = now.minus(Duration.ofDays(30));
        if (profile.getStatisticsSince().isAfter(from)) {
            from = profile.getStatisticsSince();
        }
        ProfileStatistics aggregated = profile.isAggregationEnabled()
                ? ProfileStatistics.aggregate(statistics.runs(userId, from, now), statistics.nodes(userId, from, now),
                    auditService.persistent() ? statistics.tools(userId, from, now) : List.of(), from, now,
                    ZoneId.of(profile.getTimezone()))
                : ProfileStatistics.empty();
        return new View(userId, profile.isAggregationEnabled(), profile.getStatisticsSince(), profile.getTimezone(),
                profile.getVersion(), profile.getCreatedAt(), profile.getUpdatedAt(), from, now, aggregated,
                coverage(profile.isAggregationEnabled()));
    }

    private ToolCoverage coverage(boolean enabled) {
        String reason = !enabled ? "画像停用：未查询工具历史统计"
                : !auditService.enabled() ? "审计停用：工具历史统计不可用"
                : !auditService.persistent() ? "审计落库停用：不使用内存缓冲充当历史统计"
                : "持久审计受保留期与异步丢写策略影响；诊断计数仅代表当前实例";
        return new ToolCoverage(auditService.enabled(), auditService.persistent(), auditProperties.retentionDays(),
                auditService.pendingWrites(), auditService.droppedWrites(), auditService.failedWriteBatches(),
                "current-instance", true, reason);
    }

    private void requireUser(UUID id) {
        if (!users.existsById(id)) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + id);
        }
    }

    private void lockUser(UUID id) {
        users.findByIdForProfileUpdate(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + id));
    }

    private static ZoneId zone(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "时区必须为合法 ZoneId");
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "时区必须为合法 ZoneId");
        }
    }

    private static void validateVersion(long value) {
        if (value < 0 || value == Long.MAX_VALUE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "expectedVersion 超出有效范围");
        }
    }

    private static void compareVersion(UserProfileEntity profile, long expected) {
        if (profile.getVersion() != expected) {
            throw new BizException(ErrorCode.CONFLICT, "画像版本已变化，请重新读取", Map.of("currentVersion", profile.getVersion()));
        }
    }
}
