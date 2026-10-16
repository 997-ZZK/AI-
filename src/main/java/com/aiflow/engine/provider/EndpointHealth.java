package com.aiflow.engine.provider;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单个 HTTP 端点的健康状态 + 熔断器（进程内，无外部依赖）。
 *
 * <p>为什么需要它：分级路由的前提是「知道哪一档现在是活的」。没有健康状态，云端抖动时每次请求都要先撞一遍
 * 连接超时才能落到本地兜底，用户感受到的是「变慢」而不是「降级」。
 *
 * <p>状态机：连续失败达到阈值 -> 熔断打开 {@code cooldownMillis}；冷却结束自动放行一次试探
 * （half-open 语义由「放行 + 再失败就再熔断」实现，不引入额外状态）。成功即清零并复位。
 * 探活与真实调用共用这份状态：探活失败同样计入失败次数，所以「服务已经挂了」能在第一个用户请求打进来之前被发现。
 *
 * <p>{@link #available()} 只回答「现在要不要试」。只有禁用、或熔断打开时才返回 false；
 * 冷却期一过就重新放行 —— 兜底档位不能因为一次失败而永久消失。
 */
public final class EndpointHealth {

    private final String id;
    private final boolean enabled;
    private final int failureThreshold;
    private final long cooldownMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean up = true;
    private volatile String lastError;
    private volatile Instant lastSuccessAt;
    private volatile Instant lastFailureAt;
    private volatile Instant lastProbeAt;
    private volatile Instant circuitOpenUntil;

    public EndpointHealth(String id, boolean enabled, int failureThreshold, long cooldownMillis) {
        this.id = id;
        this.enabled = enabled;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMillis = Math.max(0, cooldownMillis);
    }

    public String id() {
        return id;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 现在是否可以尝试调用：禁用 / 最近一次信号是故障 / 熔断打开，三者任一为 false。
     *
     * <p>为什么不只看熔断：探活每 15s 一次，它已经把「服务没起」这个事实拿到了。
     * 报告层要据此如实回答「现在用哪一档」，路由层也要据此跳过必然失败的尝试 ——
     * 状态接口把挂掉的档位报成可用，比报错更糟，因为它会让人以为降级没发生。
     *
     * <p>那「一直失败就永远不再试」怎么办？两层兜底：探活成功会把状态置回 up（默认开启），
     * 而 {@code ProviderRouter} 在所有档位都不可用时仍会按优先级依次试一遍。
     */
    public boolean available() {
        return enabled && up && !circuitOpen();
    }

    public boolean circuitOpen() {
        Instant openUntil = circuitOpenUntil;
        return openUntil != null && Instant.now().isBefore(openUntil);
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        up = true;
        lastError = null;
        lastSuccessAt = Instant.now();
        circuitOpenUntil = null;
    }

    public void recordProbeSuccess() {
        lastProbeAt = Instant.now();
        recordSuccess();
    }

    public void recordFailure(String error) {
        up = false;
        lastError = error;
        lastFailureAt = Instant.now();
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            circuitOpenUntil = Instant.now().plusMillis(cooldownMillis);
        }
    }

    public void recordProbeFailure(String error) {
        lastProbeAt = Instant.now();
        recordFailure(error);
    }

    public Snapshot snapshot() {
        return new Snapshot(id, enabled, up, circuitOpen(), consecutiveFailures.get(), lastError,
                lastSuccessAt, lastFailureAt, lastProbeAt);
    }

    /** 只读快照，供状态接口使用。时间字段为 null 表示「从未发生」。 */
    public record Snapshot(String id, boolean enabled, boolean up, boolean open, int consecutiveFailures,
                           String lastError, Instant lastSuccessAt, Instant lastFailureAt, Instant lastProbeAt) {

        public String state() {
            if (!enabled) {
                return "disabled";
            }
            if (open) {
                return "open";
            }
            return up ? "up" : "down";
        }
    }
}