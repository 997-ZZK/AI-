package com.aiflow.engine.run;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Carries the claimed owner from the worker into node threads and persistence callbacks. */
@Component
public class RunExecutionRegistry {

    private static final RunExecutionGuard UNGUARDED = new RunExecutionGuard() {
        @Override
        public void check() {
        }

        @Override
        public <T> T write(Supplier<T> action) {
            return action.get();
        }
    };

    private final Map<UUID, Registration> registrations = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<UUID, RunExecutionGuard>> contexts = new ThreadLocal<>();

    public Registration register(UUID runId, RunExecutionGuard guard) {
        Registration registration = new Registration(runId, Objects.requireNonNull(guard));
        Registration previous = registrations.put(runId, registration);
        if (previous != null) {
            previous.close();
        }
        return registration;
    }

    public RunExecutionGuard capture(UUID runId) {
        Map<UUID, RunExecutionGuard> context = contexts.get();
        if (context != null && context.containsKey(runId)) {
            return context.get(runId);
        }
        Registration registration = registrations.get(runId);
        return registration == null ? UNGUARDED : registration;
    }

    public Scope bind(UUID runId) {
        return bind(runId, capture(runId));
    }

    /** Administrative cancellation holds the dispatch rows itself, outside any worker lease. */
    public Scope bindUnguarded(UUID runId) {
        return bind(runId, UNGUARDED);
    }

    /** Bind a captured guard, rather than looking up a newer owner after a future starts late. */
    public Scope bind(UUID runId, RunExecutionGuard guard) {
        Map<UUID, RunExecutionGuard> previous = contexts.get();
        Map<UUID, RunExecutionGuard> next = previous == null ? new HashMap<>() : new HashMap<>(previous);
        next.put(runId, guard);
        contexts.set(next);
        return () -> {
            if (previous == null) {
                contexts.remove();
            } else {
                contexts.set(previous);
            }
        };
    }

    public void check(UUID runId) {
        capture(runId).check();
    }

    public <T> T write(UUID runId, Supplier<T> action) {
        return capture(runId).write(action);
    }

    public void write(UUID runId, Runnable action) {
        write(runId, () -> {
            action.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public final class Registration implements RunExecutionGuard, AutoCloseable {

        private final UUID runId;
        private final RunExecutionGuard delegate;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(UUID runId, RunExecutionGuard delegate) {
            this.runId = runId;
            this.delegate = delegate;
        }

        @Override
        public void check() {
            ensureActive();
            delegate.check();
        }

        @Override
        public <T> T write(Supplier<T> action) {
            ensureActive();
            return delegate.write(action);
        }

        private void ensureActive() {
            if (!active.get()) {
                throw new RunLeaseLostException("运行执行租约已释放: " + runId);
            }
        }

        @Override
        public void close() {
            active.set(false);
            registrations.remove(runId, this);
        }
    }
}
