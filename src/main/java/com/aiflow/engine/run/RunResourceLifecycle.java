package com.aiflow.engine.run;

import java.util.UUID;

/**
 * Resources that live only for one continuous execution segment of a run.
 *
 * <p>The execution engine opens the resources before it starts the segment and
 * closes them from its {@code finally} path.  Implementations must reject
 * writes after {@link #end(UUID)}; this is the fence that prevents a timed out
 * node from publishing data into a later execution of the same run.</p>
 */
public interface RunResourceLifecycle {

    void begin(UUID runId);

    void end(UUID runId);
}
