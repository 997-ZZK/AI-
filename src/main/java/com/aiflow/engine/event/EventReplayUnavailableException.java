package com.aiflow.engine.event;

public class EventReplayUnavailableException extends RuntimeException {
    public EventReplayUnavailableException() {
        super("事件回放存在缺口或已过期，请读取运行快照后重新连接");
    }
}
