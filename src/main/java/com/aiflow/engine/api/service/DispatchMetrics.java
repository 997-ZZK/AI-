package com.aiflow.engine.api.service;

import com.aiflow.engine.config.DispatchProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/** Scrapes read cached samples, so Prometheus cannot increase database query traffic. */
@Component
public class DispatchMetrics {
    private final JdbcTemplate jdbc;
    private final DispatchProperties properties;
    private volatile double queued;
    private volatile double running;
    private volatile double pendingAge;
    private final io.micrometer.core.instrument.Counter sampleFailures;

    public DispatchMetrics(JdbcTemplate jdbc, DispatchProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.properties = properties;
        meters.gauge("aiflow.dispatch.queued", this, value -> value.queued);
        meters.gauge("aiflow.dispatch.running", this, value -> value.running);
        meters.gauge("aiflow.dispatch.pending.age.seconds", this, value -> value.pendingAge);
        sampleFailures = meters.counter("aiflow.dispatch.metrics.sample.failures");
    }

    @Scheduled(fixedDelay = 5000)
    public void sample() {
        if (!properties.enabled()) return;
        try {
            jdbc.query("select status, count(*) as total, min(created_at) as oldest "
                    + "from session_dispatch_tasks where status in ('QUEUED', 'RUNNING') group by status",
                    result -> {
                        double nextQueued = 0;
                        double nextRunning = 0;
                        double nextAge = 0;
                        while (result.next()) {
                            if ("QUEUED".equals(result.getString("status"))) {
                                nextQueued = result.getLong("total");
                                Timestamp oldest = result.getTimestamp("oldest");
                                nextAge = oldest == null ? 0 : Math.max(0,
                                        Duration.between(oldest.toInstant(), Instant.now()).toMillis() / 1000.0);
                            } else {
                                nextRunning = result.getLong("total");
                            }
                        }
                        queued = nextQueued;
                        running = nextRunning;
                        pendingAge = nextAge;
                        return null;
                    });
        } catch (RuntimeException unavailable) {
            sampleFailures.increment();
        }
    }
}
