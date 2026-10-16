package com.aiflow.engine.integration.event;

import com.aiflow.engine.event.EventCursorException;
import com.aiflow.engine.event.EventPublishException;
import com.aiflow.engine.event.EventReplayUnavailableException;
import com.aiflow.engine.event.EventStreamReadException;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** One ordinary XREAD reader per locally subscribed run: every instance receives the full stream. */
public class RedisRunEventBus implements RunEventBus, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RedisRunEventBus.class);
    private static final DefaultRedisScript<String> PUBLISH = new DefaultRedisScript<>("""
            local streamType = redis.call('TYPE', KEYS[1]).ok
            local seqType = redis.call('TYPE', KEYS[2]).ok
            if (streamType ~= 'none' and streamType ~= 'stream') or
               (seqType ~= 'none' and seqType ~= 'string') then
              return redis.error_reply('event key type mismatch')
            end
            redis.call('INCR', KEYS[2])
            local seq = redis.call('GET', KEYS[2])
            redis.call('XADD', KEYS[1], 'MAXLEN', '=', ARGV[2], seq .. '-0', 'event', ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return seq
            """, String.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SNAPSHOT = new DefaultRedisScript<>("""
            local latest = redis.call('GET', KEYS[2]) or '0'
            local first = redis.call('XRANGE', KEYS[1], '-', '+', 'COUNT', 1)
            local firstId = ''
            if #first > 0 then firstId = first[1][1] end
            local events = redis.call('XRANGE', KEYS[1], '(' .. ARGV[1] .. '-0', '+', 'COUNT', ARGV[2])
            return {latest, firstId, events}
            """, List.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RedisEventProperties properties;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final LongAdder publishFailures = new LongAdder();
    private final LongAdder readFailures = new LongAdder();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RedisRunEventBus(StringRedisTemplate redis, ObjectMapper mapper, RedisEventProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
    }

    public static String streamKey(String runId) { return "aiflow:events:{" + runId + "}:stream"; }
    public static String sequenceKey(String runId) { return "aiflow:events:{" + runId + "}:seq"; }

    @Override
    public RunEvent publish(RunEvent event) {
        try {
            String payload = mapper.writeValueAsString(event.withSeq(0));
            String seq = redis.execute(PUBLISH, List.of(streamKey(event.runId()), sequenceKey(event.runId())),
                    payload, String.valueOf(properties.maxEntries()), String.valueOf(properties.retention().toMillis()));
            if (seq == null) {
                publishFailures.increment();
                throw new EventPublishException(new IllegalStateException("Redis did not acknowledge event publication"));
            }
            return event.withSeq(Long.parseLong(seq));
        } catch (DataAccessException | JsonProcessingException | NumberFormatException failure) {
            publishFailures.increment();
            throw new EventPublishException(failure);
        }
    }

    @Override
    public Subscription subscribe(String runId, long fromSeq, Consumer<RunEvent> consumer) {
        return subscribe(runId, fromSeq, consumer, ignored -> { });
    }

    @Override
    public Subscription subscribe(String runId, long fromSeq, Consumer<RunEvent> consumer,
                                  Consumer<RuntimeException> onError) {
        if (fromSeq < 0) throw new EventCursorException();
        if (closed.get()) throw new IllegalStateException("event bus is closed");
        // A retired channel may still be referenced by a waiting subscriber. Retry under its lock.
        while (true) {
            Channel channel = channels.computeIfAbsent(runId, Channel::new);
            channel.lock.lock();
            try {
                if (closed.get()) throw new IllegalStateException("event bus is closed");
                if (channel.retired) continue;
                Snapshot snapshot = snapshot(runId, fromSeq);
                Subscriber subscriber = new Subscriber(consumer, onError, fromSeq);
                for (RunEvent event : snapshot.events()) subscriber.accept(event);
                channel.subscribers.add(subscriber);
                if (channel.reader == null) {
                    channel.cursor = snapshot.latest();
                    channel.reader = Thread.ofVirtual().name("events-" + runId).unstarted(() -> read(channel));
                    channel.reader.start();
                }
                return () -> remove(channel, subscriber);
            } catch (RuntimeException failure) {
                if (!closed.get() && !channel.retired && (failure instanceof EventStreamReadException
                        || failure instanceof EventReplayUnavailableException)) {
                    readFailures.increment();
                    logReadFailure("subscribe", failure);
                }
                if (channel.subscribers.isEmpty()) retire(channel);
                throw failure;
            } finally {
                channel.lock.unlock();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Snapshot snapshot(String runId, long cursor) {
        try {
            List<Object> values = redis.execute(SNAPSHOT, List.of(streamKey(runId), sequenceKey(runId)),
                    String.valueOf(cursor), String.valueOf(properties.maxEntries()));
            if (values == null || values.size() != 3) {
                throw new EventStreamReadException(new IllegalStateException("invalid Redis event snapshot"));
            }
            long latest = Long.parseLong(values.get(0).toString());
            if (cursor > latest) throw new EventCursorException();
            String firstId = values.get(1).toString();
            if (latest > 0 && firstId.isEmpty()) throw new EventReplayUnavailableException();
            if (!firstId.isEmpty() && cursor < sequence(firstId) - 1) throw new EventReplayUnavailableException();
            List<RunEvent> events = new ArrayList<>();
            long expected = cursor + 1;
            for (Object entryObject : (List<?>) values.get(2)) {
                List<?> entry = (List<?>) entryObject;
                List<?> fields = (List<?>) entry.get(1);
                String payload = null;
                for (int index = 0; index < fields.size(); index += 2) {
                    if ("event".equals(fields.get(index).toString())) payload = fields.get(index + 1).toString();
                }
                RunEvent event = decode(runId, entry.get(0).toString(), payload);
                if (event.seq() != expected++) throw new EventReplayUnavailableException();
                events.add(event);
            }
            if (expected - 1 != latest) throw new EventReplayUnavailableException();
            return new Snapshot(latest, events);
        } catch (DataAccessException failure) {
            throw new EventStreamReadException(failure);
        } catch (NumberFormatException | ClassCastException failure) {
            throw new EventStreamReadException(failure);
        }
    }

    private void read(Channel channel) {
        int failures = 0;
        RedisConnection connection = null;
        try {
            while (!closed.get() && !channel.retired) {
                String operation = "connection-acquire";
                try {
                    if (connection == null) {
                        connection = Objects.requireNonNull(redis.getConnectionFactory(), "Redis connection factory is required")
                                .getConnection();
                    }
                    // A connection acquired after retirement must be released without starting another BLOCK.
                    if (closed.get() || channel.retired) return;
                    // RedisTemplate releases the dedicated blocking connection after each callback. Keeping
                    // this wrapper for the reader lifetime also keeps Lettuce's dedicated XREAD connection.
                    operation = "xread";
                    List<ByteRecord> records = connection.streamCommands().xRead(
                            StreamReadOptions.empty().count(properties.readBatchSize()).block(properties.readBlock()),
                            StreamOffset.create(redis.getStringSerializer().serialize(streamKey(channel.runId)),
                                    ReadOffset.from(channel.cursor + "-0")));
                    // Closing the last subscription interrupts XREAD. Its result is no longer relevant.
                    if (closed.get() || channel.retired) return;
                    List<RunEvent> events = new ArrayList<>();
                    if (records == null || records.isEmpty()) {
                        // Also detects TTL expiry and counter-without-stream gaps during an idle XREAD.
                        operation = "idle-snapshot";
                        events.addAll(snapshot(channel.runId, channel.cursor).events());
                    } else {
                        for (ByteRecord bytes : records) {
                            MapRecord<String, String, String> record = bytes.deserialize(redis.getStringSerializer());
                            events.add(decode(channel.runId, record.getId().getValue(), record.getValue().get("event")));
                        }
                    }
                    channel.lock.lock();
                    try {
                        if (closed.get() || channel.retired) return;
                        for (RunEvent event : events) {
                            if (event.seq() != channel.cursor + 1) throw new EventReplayUnavailableException();
                            for (Subscriber subscriber : List.copyOf(channel.subscribers)) {
                                try {
                                    subscriber.accept(event);
                                } catch (RuntimeException failure) {
                                    channel.subscribers.remove(subscriber);
                                    subscriber.fail(failure);
                                }
                            }
                            channel.cursor = event.seq();
                        }
                        if (channel.subscribers.isEmpty()) retire(channel);
                    } finally {
                        channel.lock.unlock();
                    }
                    failures = 0;
                } catch (EventReplayUnavailableException | EventCursorException failure) {
                    if (!recordReadFailure(channel, operation, failure)) return;
                    failChannel(channel, failure);
                    return;
                } catch (DataAccessException | EventStreamReadException failure) {
                    if (!recordReadFailure(channel, operation, failure)) return;
                    RedisConnection failedConnection = connection;
                    connection = null;
                    if (!closeReadConnection(channel, failedConnection)) return;
                    if (++failures >= properties.readRetryLimit()) {
                        failChannel(channel, new EventStreamReadException(failure));
                        return;
                    }
                    try {
                        Thread.sleep(Math.min(1000L, 100L * failures));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        if (!closed.get() && !channel.retired) failChannel(channel, new EventStreamReadException(interrupted));
                        return;
                    }
                } catch (RuntimeException failure) {
                    if (!recordReadFailure(channel, operation, failure)) return;
                    failChannel(channel, new EventStreamReadException(failure));
                    return;
                }
            }
        } finally {
            // Only the reader owns/closes this connection; never block an unsubscribe holding channel.lock.
            closeReadConnection(channel, connection);
        }
    }

    private boolean closeReadConnection(Channel channel, RedisConnection connection) {
        if (connection == null) return true;
        try {
            connection.close();
            return true;
        } catch (RuntimeException failure) {
            // A failed release during active recovery must not disappear behind the original read failure.
            if (recordReadFailure(channel, "connection-release", failure)) {
                failChannel(channel, new EventStreamReadException(failure));
            }
            return false;
        }
    }

    private boolean recordReadFailure(Channel channel, String operation, RuntimeException failure) {
        channel.lock.lock();
        try {
            if (closed.get() || channel.retired) return false;
            readFailures.increment();
            logReadFailure(operation, failure);
            return true;
        } finally {
            channel.lock.unlock();
        }
    }

    private static void logReadFailure(String operation, RuntimeException failure) {
        Throwable cause = failure.getCause();
        // Exception messages, stack traces and event identifiers can contain application data.
        log.warn("Redis event read failed operation={} failure={} cause={}", operation,
                failure.getClass().getName(), cause == null ? "none" : cause.getClass().getName());
    }

    private RunEvent decode(String runId, String id, String payload) {
        try {
            RunEvent event = mapper.readValue(payload, RunEvent.class).withSeq(sequence(id));
            if (!runId.equals(event.runId())) throw new EventReplayUnavailableException();
            return event;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new EventReplayUnavailableException();
        }
    }

    private static long sequence(String id) {
        return Long.parseLong(id.substring(0, id.indexOf('-')));
    }

    private void remove(Channel channel, Subscriber subscriber) {
        channel.lock.lock();
        try {
            channel.subscribers.remove(subscriber);
            if (channel.subscribers.isEmpty()) retire(channel);
        } finally {
            channel.lock.unlock();
        }
    }

    private void retire(Channel channel) {
        channel.retired = true;
        channels.remove(channel.runId, channel);
        if (channel.reader != null && channel.reader != Thread.currentThread()) channel.reader.interrupt();
    }

    private void failChannel(Channel channel, RuntimeException failure) {
        failChannel(channel, failure, false);
    }

    private void failChannel(Channel channel, RuntimeException failure, boolean stopping) {
        channel.lock.lock();
        try {
            if (channel.retired || (!stopping && closed.get())) return;
            List<Subscriber> subscribers = List.copyOf(channel.subscribers);
            channel.subscribers.clear();
            retire(channel);
            for (Subscriber subscriber : subscribers) subscriber.fail(failure);
        } finally {
            channel.lock.unlock();
        }
    }

    @Override
    public Status status() {
        int subscriptions = channels.values().stream().mapToInt(channel -> {
            channel.lock.lock();
            try { return channel.subscribers.size(); } finally { channel.lock.unlock(); }
        }).sum();
        return new Status("redis", publishFailures.sum(), readFailures.sum(), channels.size(), subscriptions);
    }

    @Override
    public void close() {
        closed.set(true);
        for (Channel channel : List.copyOf(channels.values())) {
            failChannel(channel, new EventStreamReadException(new IllegalStateException("event bus stopped")), true);
        }
    }

    private record Snapshot(long latest, List<RunEvent> events) { }
    private static final class Subscriber {
        private final Consumer<RunEvent> consumer;
        private final Consumer<RuntimeException> onError;
        private long cursor;
        private Subscriber(Consumer<RunEvent> consumer, Consumer<RuntimeException> onError, long cursor) {
            this.consumer = consumer;
            this.onError = onError;
            this.cursor = cursor;
        }
        private void accept(RunEvent event) {
            if (event.seq() <= cursor) return;
            if (event.seq() != cursor + 1) throw new EventReplayUnavailableException();
            consumer.accept(event);
            cursor = event.seq();
        }
        private void fail(RuntimeException failure) {
            try { onError.accept(failure); } catch (RuntimeException ignored) { /* connection already gone */ }
        }
    }
    private static final class Channel {
        private final String runId;
        private final ReentrantLock lock = new ReentrantLock();
        private final List<Subscriber> subscribers = new ArrayList<>();
        private volatile boolean retired;
        private volatile long cursor;
        private Thread reader;
        private Channel(String runId) { this.runId = runId; }
    }
}
