package com.aiflow.engine.api.stream;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.event.EventCursorException;
import com.aiflow.engine.event.EventReplayUnavailableException;
import com.aiflow.engine.event.EventStreamReadException;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventBus;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.integration.event.RedisEventProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

@Service
public class RunStreamService {
    private static final Logger log = LoggerFactory.getLogger(RunStreamService.class);
    private final RunEventBus eventBus;
    private final ObjectMapper mapper;
    private final EngineProperties properties;
    private final RedisEventProperties eventProperties;
    private final Map<SseEmitter, Connection> connections = new ConcurrentHashMap<>();
    private final LongAdder overflowCount = new LongAdder();

    public RunStreamService(RunEventBus eventBus, ObjectMapper mapper, EngineProperties properties,
                            RedisEventProperties eventProperties) {
        this.eventBus = eventBus;
        this.mapper = mapper;
        this.properties = properties;
        this.eventProperties = eventProperties;
    }

    public SseEmitter subscribe(String runId, long lastEventId) {
        return subscribe(runId, lastEventId, new SseEmitter(properties.sseTimeoutMs()));
    }

    // Package access permits testing a genuinely blocked/failed transport without a servlet container.
    SseEmitter subscribe(String runId, long lastEventId, SseEmitter emitter) {
        Connection connection = new Connection(runId, emitter);
        emitter.onCompletion(connection::cleanup);
        emitter.onTimeout(connection::cleanup);
        emitter.onError(ignored -> connection.cleanup());
        connections.put(emitter, connection);
        try {
            connection.subscription = eventBus.subscribe(runId, lastEventId, connection::offerEvent, connection::streamError);
            if (connection.closed.get()) {
                connection.subscription.close();
                throw new BizException(ErrorCode.CONFLICT, "事件回放超过连接队列容量，请读取运行快照");
            }
            connection.sender.start();
            return emitter;
        } catch (EventCursorException failure) {
            connection.cleanup();
            throw new BizException(ErrorCode.BAD_REQUEST, failure.getMessage());
        } catch (EventReplayUnavailableException failure) {
            connection.cleanup();
            throw new BizException(ErrorCode.CONFLICT, failure.getMessage());
        } catch (EventStreamReadException failure) {
            connection.cleanup();
            throw new BizException(ErrorCode.CAPABILITY_UNAVAILABLE, failure.getMessage());
        } catch (RuntimeException failure) {
            connection.cleanup();
            throw failure;
        }
    }

    @Scheduled(fixedDelayString = "${aiflow.engine.sse-heartbeat-ms:15000}")
    public void heartbeat() {
        for (Connection connection : connections.values()) connection.offer(new Message(null, "PING", "{}", false));
    }

    public Status status() {
        return new Status(eventBus.status(), connections.size(), overflowCount.sum());
    }

    @PreDestroy
    public void close() {
        for (Connection connection : List.copyOf(connections.values())) connection.closeAsync();
    }

    public record Status(RunEventBus.Status bus, int connectionCount, long sseOverflow) { }
    private record Message(String id, String type, String payload, boolean terminal) { }

    private final class Connection {
        private final String runId;
        private final SseEmitter emitter;
        private final ArrayBlockingQueue<Message> queue = new ArrayBlockingQueue<>(eventProperties.sseQueueCapacity());
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread sender;
        private volatile RunEventBus.Subscription subscription;
        private volatile boolean terminating;

        private Connection(String runId, SseEmitter emitter) {
            this.runId = runId;
            this.emitter = emitter;
            this.sender = Thread.ofVirtual().name("sse-" + runId).unstarted(this::sendLoop);
        }

        private void offerEvent(RunEvent event) {
            try {
                offer(new Message(String.valueOf(event.seq()), event.type().name(), mapper.writeValueAsString(event),
                        isTerminal(event.type())));
            } catch (JsonProcessingException failure) {
                log.error("sse event serialization failed: runId={} seq={}", runId, event.seq());
                streamError(new EventReplayUnavailableException());
            }
        }

        private boolean isTerminal(RunEventType type) {
            return switch (type) {
                case RUN_SUCCEEDED, RUN_DEGRADED, RUN_FAILED, RUN_CANCELED -> true;
                default -> false;
            };
        }

        private void offer(Message message) {
            if (closed.get() || terminating) return;
            if (!queue.offer(message)) {
                overflowCount.increment();
                closeAsync();
            }
        }

        private void streamError(RuntimeException failure) {
            if (closed.get() || terminating) return;
            terminating = true;
            queue.clear();
            String code = failure instanceof EventReplayUnavailableException ? "REPLAY_UNAVAILABLE" : "EVENTS_UNAVAILABLE";
            try {
                queue.offer(new Message(null, "STREAM_ERROR", mapper.writeValueAsString(Map.of(
                        "code", code, "snapshot", "/api/v1/runs/" + runId)), true));
            } catch (JsonProcessingException impossible) {
                closeAsync();
            }
        }

        private void sendLoop() {
            try {
                while (!closed.get()) {
                    Message message = queue.take();
                    SseEmitter.SseEventBuilder event = SseEmitter.event().name(message.type())
                            .data(message.payload(), MediaType.APPLICATION_JSON);
                    if (message.id() != null) event.id(message.id());
                    emitter.send(event);
                    if (message.terminal()) {
                        cleanup();
                        complete();
                        return;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException failure) {
                log.debug("sse connection closed: runId={}", runId);
                cleanup();
                complete();
            }
        }

        private void cleanup() {
            if (!closed.compareAndSet(false, true)) return;
            connections.remove(emitter, this);
            queue.clear();
            RunEventBus.Subscription current = subscription;
            if (current != null) current.close();
            if (sender != Thread.currentThread()) sender.interrupt();
        }

        private void closeAsync() {
            cleanup();
            // A servlet send can hold its write lock. Never wait for complete() on a publisher/reader.
            Thread.ofVirtual().name("sse-close-" + runId).start(this::complete);
        }

        private void complete() {
            try { emitter.complete(); } catch (RuntimeException alreadyGone) {
                log.debug("sse completion skipped: runId={}", runId);
            }
        }
    }
}
