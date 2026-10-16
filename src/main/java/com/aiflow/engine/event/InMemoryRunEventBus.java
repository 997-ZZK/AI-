package com.aiflow.engine.event;

import com.aiflow.engine.config.EngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "aiflow.events.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryRunEventBus implements RunEventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRunEventBus.class);

    private final int bufferSize;
    private final Map<String, RunChannel> channels = new ConcurrentHashMap<>();

    public InMemoryRunEventBus(EngineProperties properties) {
        this.bufferSize = properties.eventBufferSize();
    }

    @Override
    public RunEvent publish(RunEvent event) {
        RunChannel channel = channels.computeIfAbsent(event.runId(), key -> new RunChannel());
        RunEvent sequenced;
        channel.lock.lock();
        try {
            sequenced = event.withSeq(channel.seq.incrementAndGet());
            channel.buffer.addLast(sequenced);
            while (channel.buffer.size() > bufferSize) {
                channel.buffer.removeFirst();
            }
            for (Consumer<RunEvent> subscriber : List.copyOf(channel.subscribers)) {
                try {
                    subscriber.accept(sequenced);
                } catch (RuntimeException failure) {
                    channel.subscribers.remove(subscriber);
                    log.warn("event subscriber failed for run {}", event.runId());
                }
            }
            return sequenced;
        } finally {
            channel.lock.unlock();
        }
    }

    @Override
    public Subscription subscribe(String runId, long fromSeq, Consumer<RunEvent> consumer) {
        RunChannel channel = channels.computeIfAbsent(runId, key -> new RunChannel());
        channel.lock.lock();
        try {
            if (fromSeq < 0 || fromSeq > channel.seq.get()) {
                throw new EventCursorException();
            }
            if (!channel.buffer.isEmpty() && fromSeq < channel.buffer.getFirst().seq() - 1) {
                throw new EventReplayUnavailableException();
            }
            for (RunEvent buffered : channel.buffer) {
                if (buffered.seq() > fromSeq) {
                    consumer.accept(buffered);
                }
            }
            channel.subscribers.add(consumer);
        } finally {
            channel.lock.unlock();
        }
        return () -> channel.subscribers.remove(consumer);
    }

    @Override
    public Status status() {
        int subscriptions = channels.values().stream().mapToInt(channel -> channel.subscribers.size()).sum();
        return new Status("memory", 0, 0, 0, subscriptions);
    }

    private static final class RunChannel {
        private final AtomicLong seq = new AtomicLong();
        private final ReentrantLock lock = new ReentrantLock();
        private final ArrayDeque<RunEvent> buffer = new ArrayDeque<>();
        private final CopyOnWriteArrayList<Consumer<RunEvent>> subscribers = new CopyOnWriteArrayList<>();
    }
}
