package com.aiflow.engine.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointHealthTest {

    @Test
    void disabledEndpointIsNeverAvailable() {
        EndpointHealth health = new EndpointHealth("local", false, 3, 30_000);

        assertThat(health.available()).isFalse();
        health.recordSuccess();
        assertThat(health.available()).isFalse();
        assertThat(health.snapshot().state()).isEqualTo("disabled");
    }

    @Test
    void pendingEndpointStaysUnavailableUntilFirstSuccessfulProbe() {
        EndpointHealth health = new EndpointHealth("local", true, 3, 30_000);

        health.markPending();

        assertThat(health.available()).isFalse();
        assertThat(health.snapshot().state()).isEqualTo("down");
        health.recordProbeSuccess();
        assertThat(health.available()).isTrue();
    }

    @Test
    void consecutiveFailuresOpenCircuitUntilCooldownExpires() {
        EndpointHealth health = new EndpointHealth("cloud", true, 2, 200);

        health.recordFailure("连接超时");
        assertThat(health.circuitOpen()).as("一次失败还不该熔断").isFalse();
        assertThat(health.available()).as("已判故障的档位不再被选中，避免每次请求都白撞一次连接失败").isFalse();

        health.recordFailure("连接超时");
        assertThat(health.circuitOpen()).isTrue();
        assertThat(health.available()).isFalse();

        // 冷却结束后熔断不再拦人；真正让它恢复可用的是探活成功（见后面那个用例）
        sleep(260);
        assertThat(health.circuitOpen()).isFalse();
        health.recordProbeSuccess();
        assertThat(health.available()).isTrue();
    }

    @Test
    void successResetsFailuresAndClosesCircuit() {
        EndpointHealth health = new EndpointHealth("local", true, 1, 60_000);

        health.recordFailure("connection refused");
        assertThat(health.circuitOpen()).isTrue();

        health.recordSuccess();
        assertThat(health.circuitOpen()).isFalse();
        assertThat(health.snapshot().consecutiveFailures()).isZero();
        assertThat(health.snapshot().lastError()).isNull();
    }

    @Test
    void snapshotKeepsLastErrorForDiagnosis() {
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);

        health.recordFailure("探活失败: Connection refused");
        EndpointHealth.Snapshot snapshot = health.snapshot();

        assertThat(snapshot.state()).isEqualTo("down");
        assertThat(snapshot.lastError()).isEqualTo("探活失败: Connection refused");
        assertThat(snapshot.lastSuccessAt()).isNull();
        assertThat(snapshot.lastFailureAt()).isNotNull();
    }

    @Test
    void probeResultsShareStateWithRealCalls() {
        EndpointHealth health = new EndpointHealth("cloud", true, 1, 60_000);

        health.recordProbeFailure("探活鉴权失败 HTTP 401");

        assertThat(health.snapshot().lastProbeAt()).isNotNull();
        assertThat(health.circuitOpen()).as("探活失败同样计入熔断").isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
