package com.aiflow.engine.api;

import com.aiflow.engine.api.service.RunCoordinator;
import com.aiflow.engine.api.service.PersistentDispatchWorker;
import com.aiflow.engine.api.service.RunQueryService;
import com.aiflow.engine.api.stream.RunStreamService;
import com.aiflow.engine.common.exception.GlobalExceptionHandler;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.event.RunEvent;
import com.aiflow.engine.event.RunEventType;
import com.aiflow.engine.integration.event.RedisEventProperties;
import com.aiflow.engine.run.RunCancellationRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RunEventsControllerTest {
    private final UUID runId = UUID.randomUUID();
    private final InMemoryRunEventBus bus = new InMemoryRunEventBus(EngineProperties.defaults());

    @ParameterizedTest
    @ValueSource(strings = {"-1", "bogus", "1-0", "1.5", "+1", "", "9223372036854775808"})
    void malformedCursorReturns400(String cursor) throws Exception {
        mvc().perform(get("/api/v1/runs/{id}/events", runId).header("Last-Event-ID", cursor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void cursorAheadOfLatestReturns400AndTruncatedHistoryReturns409() throws Exception {
        mvc().perform(get("/api/v1/runs/{id}/events", runId).header("Last-Event-ID", "1"))
                .andExpect(status().isBadRequest());
        for (int index = 0; index < 2001; index++) bus.publish(RunEvent.of(runId.toString(), RunEventType.DELTA, "llm", Map.of()));
        mvc().perform(get("/api/v1/runs/{id}/events", runId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40900));
        org.assertj.core.api.Assertions.assertThat(bus.status().subscriptionCount()).isZero();
    }

    private MockMvc mvc() {
        RunQueryService query = mock(RunQueryService.class);
        when(query.exists(runId)).thenReturn(true);
        var stream = new RunStreamService(bus, new ObjectMapper().findAndRegisterModules(), EngineProperties.defaults(), RedisEventProperties.defaults());
        RunController controller = new RunController(query, stream, mock(RunCancellationRegistry.class),
                mock(RunCoordinator.class), mock(ApprovalController.class), mock(PersistentDispatchWorker.class));
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
