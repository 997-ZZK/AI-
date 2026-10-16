package com.aiflow.engine.api.stream;

import com.aiflow.engine.event.RunEventBus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventMetricsTest {
    @Test
    void exportsLiveStatusCountersAndGaugesWithOnlyTheModeTag() {
        RunEventBus bus = mock(RunEventBus.class);
        RunStreamService streams = mock(RunStreamService.class);
        RunEventBus.Status initial = new RunEventBus.Status("redis", 2, 3, 1, 4);
        when(bus.status()).thenReturn(initial);
        when(streams.status()).thenReturn(new RunStreamService.Status(initial, 4, 5));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            new EventMetrics(bus, streams, meters);
            assertThat(meters.get("aiflow.events.publish.failures").functionCounter().count()).isEqualTo(2);
            assertThat(meters.get("aiflow.events.read.failures").functionCounter().count()).isEqualTo(3);
            assertThat(meters.get("aiflow.events.readers").gauge().value()).isEqualTo(1);
            assertThat(meters.get("aiflow.events.subscriptions").gauge().value()).isEqualTo(4);
            assertThat(meters.get("aiflow.sse.connections").gauge().value()).isEqualTo(4);
            assertThat(meters.get("aiflow.sse.overflow").functionCounter().count()).isEqualTo(5);
            RunEventBus.Status updated = new RunEventBus.Status("redis", 6, 7, 0, 0);
            when(bus.status()).thenReturn(updated);
            when(streams.status()).thenReturn(new RunStreamService.Status(updated, 0, 8));
            assertThat(meters.get("aiflow.events.publish.failures").functionCounter().count()).isEqualTo(6);
            assertThat(meters.get("aiflow.sse.connections").gauge().value()).isZero();
            assertThat(meters.getMeters()).allSatisfy(meter -> {
                assertThat(meter.getId().getTags()).hasSize(1);
                assertThat(meter.getId().getTag("mode")).isEqualTo("redis");
            });
        } finally {
            meters.close();
        }
    }
}
