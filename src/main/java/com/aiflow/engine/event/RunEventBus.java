package com.aiflow.engine.event;

import java.util.function.Consumer;
import java.util.Optional;
import org.slf4j.LoggerFactory;

public interface RunEventBus {

    RunEvent publish(RunEvent event);

    /** Isolate the event transport failure only; execution/lease failures must still escape. */
    default Optional<RunEvent> publishSafely(RunEvent event) {
        try {
            return Optional.ofNullable(publish(event));
        } catch (EventPublishException failure) {
            LoggerFactory.getLogger(RunEventBus.class).warn(
                    "event publish unavailable: runId={} type={}", event.runId(), event.type());
            return Optional.empty();
        }
    }

    Subscription subscribe(String runId, long fromSeq, Consumer<RunEvent> consumer);

    default Subscription subscribe(String runId, long fromSeq, Consumer<RunEvent> consumer,
                                   Consumer<RuntimeException> onError) {
        return subscribe(runId, fromSeq, consumer);
    }

    default Status status() {
        return new Status("memory", 0, 0, 0, 0);
    }

    record Status(String mode, long publishFailures, long readFailures, int readerCount,
                  int subscriptionCount) { }

    interface Subscription {
        void close();
    }
}
