package com.aiflow.engine.event;

public class EventStreamReadException extends RuntimeException {
    public EventStreamReadException(Throwable cause) {
        super("运行事件读取不可用，请读取运行快照", cause);
    }
}
