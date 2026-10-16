package com.aiflow.engine.run;

/** Raised by a node callback that arrives after its timed attempt was fenced. */
public class RunAttemptClosedException extends RuntimeException {

    public RunAttemptClosedException() {
        super("节点执行尝试已关闭");
    }
}
