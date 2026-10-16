package com.aiflow.engine.integration;

import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.provider.HealthProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期探活。串行跑（每个探活自带 1.5s 超时，数量是个位数），失败只记 debug —— 探活失败是常态，
 * 真正的降级信号写在 {@code EndpointHealth} 里，由状态接口和路由读取。
 */
@Component
public class EndpointProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(EndpointProbeScheduler.class);

    private final HttpEndpointFactory factory;
    private final ProviderProperties properties;

    public EndpointProbeScheduler(HttpEndpointFactory factory, ProviderProperties properties) {
        this.factory = factory;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${aiflow.provider.probe.initial-delay-ms:2000}",
            fixedDelayString = "${aiflow.provider.probe.interval-ms:15000}")
    public void probeAll() {
        if (!properties.probe().enabled()) {
            return;
        }
        for (HealthProbe probe : factory.probes()) {
            try {
                boolean up = probe.probe();
                log.debug("probe {} -> {}", probe.id(), up ? "up" : "down");
            } catch (RuntimeException e) {
                log.debug("probe {} 异常: {}", probe.id(), e.toString());
            }
        }
    }
}