package com.aiflow.engine.run;

import com.aiflow.engine.node.RunCanceledException;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunExecutionRegistryTest {

    @Test
    void unguardedModeKeepsExistingBehavior() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        UUID runId = UUID.randomUUID();
        registry.check(runId);
        assertThat(registry.write(runId, () -> "saved")).isEqualTo("saved");
    }

    @Test
    void capturedOwnerCannotBorrowNewOwnersLease() throws Exception {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        UUID runId = UUID.randomUUID();
        RunExecutionRegistry.Registration first = registry.register(runId, permissive());
        RunExecutionGuard oldGuard = registry.capture(runId);
        try (RunExecutionRegistry.Registration second = registry.register(runId, permissive());
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            first.close();
            registry.check(runId);
            threads.submit(() -> {
                try (RunExecutionRegistry.Scope ignored = registry.bind(runId, oldGuard)) {
                    assertThatThrownBy(() -> registry.write(runId, () -> "stale"))
                            .isInstanceOf(RunLeaseLostException.class);
                }
            }).get();
            assertThat(registry.write(runId, () -> "new owner")).isEqualTo("new owner");
        }
    }

    @Test
    void cancellationCheckDoesNotPreventCanceledStateWrite() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        UUID runId = UUID.randomUUID();
        try (var registration = registry.register(runId, new RunExecutionGuard() {
            @Override
            public void check() {
                throw new RunCanceledException();
            }

            @Override
            public <T> T write(Supplier<T> action) {
                return action.get();
            }
        })) {
            assertThatThrownBy(() -> registry.check(runId)).isInstanceOf(RunCanceledException.class);
            assertThat(registry.write(runId, () -> RunStatus.CANCELED)).isEqualTo(RunStatus.CANCELED);
        }
    }

    private RunExecutionGuard permissive() {
        return new RunExecutionGuard() {
            @Override
            public void check() {
            }

            @Override
            public <T> T write(Supplier<T> action) {
                return action.get();
            }
        };
    }
}
