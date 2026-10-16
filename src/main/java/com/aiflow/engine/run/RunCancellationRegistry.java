package com.aiflow.engine.run;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RunCancellationRegistry {

    private final Map<UUID, AtomicBoolean> flags = new ConcurrentHashMap<>();

    public void register(UUID runId) {
        flags.putIfAbsent(runId, new AtomicBoolean(false));
    }

    public boolean cancel(UUID runId) {
        AtomicBoolean flag = flags.get(runId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    public boolean isCanceled(UUID runId) {
        AtomicBoolean flag = flags.get(runId);
        return flag != null && flag.get();
    }

    public void clear(UUID runId) {
        flags.remove(runId);
    }
}
