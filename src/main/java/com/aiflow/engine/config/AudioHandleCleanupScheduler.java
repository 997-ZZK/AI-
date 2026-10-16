package com.aiflow.engine.config;

import com.aiflow.engine.audio.AudioHandleStore;
import org.springframework.scheduling.annotation.Scheduled;

/** Spring-only 30-second safety sweep; the handle store itself stays pure Java. */
public final class AudioHandleCleanupScheduler {

    private final AudioHandleStore store;

    public AudioHandleCleanupScheduler(AudioHandleStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelay = 30_000)
    public void sweep() {
        store.sweepExpired();
    }
}
