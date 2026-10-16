package com.aiflow.engine.session;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "aiflow.engine.session.redis-lock-enabled", havingValue = "false", matchIfMissing = true)
public class NoopSessionLock implements SessionLock {

    @Override
    public Optional<Handle> tryAcquire(String key) {
        return Optional.of(() -> {
        });
    }
}
