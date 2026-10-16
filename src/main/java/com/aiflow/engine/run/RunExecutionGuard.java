package com.aiflow.engine.run;

import java.util.function.Supplier;

/** Checks ownership at execution boundaries and fences writes in the lease transaction. */
public interface RunExecutionGuard {

    void check();

    /** Cancellation may stop execution, but must still allow a valid owner to record CANCELED. */
    <T> T write(Supplier<T> action);
}
