package com.aiflow.engine.session;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public class SerialExecutor implements Executor {

    private final Executor delegate;
    private final int capacity;
    private final Queue<Runnable> queue = new ArrayDeque<>();
    private Runnable active;

    public SerialExecutor(Executor delegate, int capacity) {
        this.delegate = delegate;
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public synchronized void execute(Runnable task) {
        if (queue.size() >= capacity) {
            throw new RejectedExecutionException("session queue is full (capacity=" + capacity + ")");
        }
        queue.offer(() -> {
            try {
                task.run();
            } finally {
                scheduleNext();
            }
        });
        if (active == null) {
            scheduleNext();
        }
    }

    public synchronized boolean isIdle() {
        return active == null && queue.isEmpty();
    }

    private synchronized void scheduleNext() {
        active = queue.poll();
        if (active != null) {
            delegate.execute(active);
        }
    }
}
