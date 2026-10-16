package com.aiflow.engine.event;

public class EventCursorException extends RuntimeException {
    public EventCursorException() {
        super("事件游标必须为不超过最新序号的非负十进制整数");
    }
}
