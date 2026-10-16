package com.aiflow.engine.memory;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 记忆维护（滚动摘要）的专用执行器：虚拟线程，**不占会话的串行队列**。
 *
 * <p>为什么不复用 {@code SessionDispatcher}：那是「同一会话的用户请求必须严格有序」的队列，
 * 一次摘要要调模型、可能几秒；把它排进会话队列，用户下一句话就得等它跑完。
 * 摘要与运行之间是可以并发的（只有读），顺序问题由 {@code summarized_count} 的条件更新管。
 *
 * <p>为什么不直接注入 {@code ExecutorService} 类型的 bean：容器里已有一个（{@code nodeExecutorService}），
 * 两个同类型 bean 会让按类型注入的既有组件直接装配失败。用独立类型把这件事说清楚。
 */
@Component
public class MemoryMaintenanceExecutor {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public void submit(Runnable task) {
        executor.submit(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
