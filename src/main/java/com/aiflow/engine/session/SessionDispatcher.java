package com.aiflow.engine.session;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.common.trace.MdcScope;
import com.aiflow.engine.config.EngineProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Component
public class SessionDispatcher {

    private final ExecutorService executorService;
    private final EngineProperties properties;
    private final Map<String, SerialExecutor> queues = new ConcurrentHashMap<>();

    public SessionDispatcher(ExecutorService executorService, EngineProperties properties) {
        this.executorService = executorService;
        this.properties = properties;
    }

    public void submit(String sessionId, Runnable task) {
        Map<String, String> mdc = MdcScope.capture();
        SerialExecutor serialExecutor = queues.computeIfAbsent(sessionId,
                key -> new SerialExecutor(executorService, properties.sessionQueueCapacity()));
        Runnable wrapped = () -> {
            try (MdcScope scope = MdcScope.restore(mdc)) {
                task.run();
            }
        };
        try {
            serialExecutor.execute(wrapped);
        } catch (RejectedExecutionException e) {
            throw new BizException(ErrorCode.RATE_LIMITED, "会话排队已满，请稍后重试（session=" + sessionId + "）");
        }
    }
}
