package com.aiflow.engine.run;

/** Ownership loss is neither a node failure nor an explicit user cancellation. */
public class RunLeaseLostException extends RuntimeException {

    public RunLeaseLostException(String message) {
        super(message);
    }
}
