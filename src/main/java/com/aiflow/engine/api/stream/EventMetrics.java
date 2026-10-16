package com.aiflow.engine.api.stream;

import com.aiflow.engine.event.RunEventBus;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Bridge the same local status counters to Prometheus, without per-run cardinality. */
@Component
public class EventMetrics {
    public EventMetrics(RunEventBus bus, RunStreamService streams, MeterRegistry meters) {
        String mode = bus.status().mode();
        FunctionCounter.builder("aiflow.events.publish.failures", bus, value -> value.status().publishFailures())
                .tag("mode", mode).description("Event publications unavailable on this instance").register(meters);
        FunctionCounter.builder("aiflow.events.read.failures", bus, value -> value.status().readFailures())
                .tag("mode", mode).description("Event read failures or unavailable replay observations").register(meters);
        Gauge.builder("aiflow.events.readers", bus, value -> value.status().readerCount())
                .tag("mode", mode).description("Runs with an active local stream reader").register(meters);
        Gauge.builder("aiflow.events.subscriptions", bus, value -> value.status().subscriptionCount())
                .tag("mode", mode).description("Current local event subscriptions").register(meters);
        Gauge.builder("aiflow.sse.connections", streams, value -> value.status().connectionCount())
                .tag("mode", mode).description("Current local SSE connections").register(meters);
        FunctionCounter.builder("aiflow.sse.overflow", streams, value -> value.status().sseOverflow())
                .tag("mode", mode).description("SSE connections dropped for bounded queue overflow").register(meters);
    }
}
