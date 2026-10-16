package com.aiflow.engine.event;

/** A failed event transport is separate from the durable execution outcome. */
public class EventPublishException extends RuntimeException {
    public EventPublishException(Throwable cause) {
        super("运行事件发布不可用", cause);
    }
}
