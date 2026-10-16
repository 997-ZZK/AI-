package com.aiflow.engine.session;

import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.config.EngineProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionDispatcherTest {

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void keepsOrderWithinSession() throws Exception {
        SessionDispatcher dispatcher = new SessionDispatcher(executorService, EngineProperties.defaults());
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(3);
        for (int index = 1; index <= 3; index++) {
            int taskIndex = index;
            dispatcher.submit("session-1", () -> {
                order.add("task-" + taskIndex);
                done.countDown();
            });
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly("task-1", "task-2", "task-3");
    }

    @Test
    void runsDifferentSessionsInParallel() throws Exception {
        SessionDispatcher dispatcher = new SessionDispatcher(executorService, EngineProperties.defaults());
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        dispatcher.submit("session-a", () -> {
            started.countDown();
            await(release);
        });
        dispatcher.submit("session-b", () -> {
            started.countDown();
            await(release);
        });

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
    }

    @Test
    void rejectsWhenQueueIsFull() {
        EngineProperties tiny = new EngineProperties(50, 60, 2, 200, 1, 1_800_000, 15_000,
                "basic-chat", new String[]{"basic-chat"}, "fallback", 2000,
                new EngineProperties.Session(false, 30_000));
        SessionDispatcher dispatcher = new SessionDispatcher(executorService, tiny);
        CountDownLatch release = new CountDownLatch(1);
        dispatcher.submit("session-x", () -> await(release));
        dispatcher.submit("session-x", () -> {
        });

        assertThatThrownBy(() -> dispatcher.submit("session-x", () -> {
        }))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("排队已满");
        release.countDown();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
