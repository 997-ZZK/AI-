package com.aiflow.engine.node;

public interface RunControl {

    boolean isCanceled();

    default void check() {
        if (isCanceled()) {
            throw new RunCanceledException();
        }
    }

    default void throwIfCanceled() {
        check();
    }
}
