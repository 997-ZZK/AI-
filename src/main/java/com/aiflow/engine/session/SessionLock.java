package com.aiflow.engine.session;

import java.util.Optional;

public interface SessionLock {

    Optional<Handle> tryAcquire(String key);

    interface Handle extends AutoCloseable {

        /** Legacy and no-op handles remain valid without adding another abstract method. */
        default boolean isValid() {
            return true;
        }

        @Override
        void close();
    }
}
