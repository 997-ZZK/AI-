package com.aiflow.engine.node;

public class RunCanceledException extends RuntimeException {

    public RunCanceledException() {
        super("运行已被取消");
    }
}
