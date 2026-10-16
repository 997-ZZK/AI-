package com.aiflow.engine.session;

public enum DispatchStatus {
    QUEUED, RUNNING, DONE, FAILED, CANCELED;

    public boolean terminal() {
        return this == DONE || this == FAILED || this == CANCELED;
    }
}
