package com.aiflow.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Control state, not a second copy of the user's history or preferences. */
@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "aggregation_enabled", nullable = false)
    private boolean aggregationEnabled;

    @Column(name = "statistics_since", nullable = false)
    private Instant statisticsSince;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfileEntity() {
    }

    public UserProfileEntity(UUID userId, Instant now) {
        this.userId = userId;
        this.aggregationEnabled = false;
        this.statisticsSince = now;
        this.timezone = "UTC";
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Caller has locked the user row and compared the expected version. */
    public void update(boolean enabled, String zone, Instant now) {
        if (enabled && !aggregationEnabled) {
            statisticsSince = now;
        }
        aggregationEnabled = enabled;
        timezone = zone;
        version++;
        updatedAt = now;
    }

    public void reset(Instant now) {
        aggregationEnabled = false;
        statisticsSince = now;
        version++;
        updatedAt = now;
    }

    public UUID getUserId() { return userId; }
    public boolean getAggregationEnabled() { return aggregationEnabled; }
    public boolean isAggregationEnabled() { return aggregationEnabled; }
    public Instant getStatisticsSince() { return statisticsSince; }
    public String getTimezone() { return timezone; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
