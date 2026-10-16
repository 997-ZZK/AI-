package com.aiflow.engine.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises MVC exception selection, committed SSE output and real server-error responses. */
class GlobalExceptionHandlerSseTest {
    private static final String SENT_EVENT = "event:PING\ndata:{}\n\n";
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void stopLogging() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
    }

    @Test
    void plainLocalizedIOExceptionAfterSseCommitDoesNotRewriteJsonOrLogAnErrorStack() throws Exception {
        mvc.perform(get("/failure/committed-sse").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(SENT_EVENT))
                .andExpect(result -> {
                    assertThat(result.getResponse().isCommitted()).isTrue();
                    assertThat(result.getResolvedException()).isExactlyInstanceOf(IOException.class);
                });
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).isEqualTo("SSE transport closed after response commitment");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void IOExceptionBeforeSseCommitStillReturnsA500JsonErrorDespitePresetSseContentType() throws Exception {
        mvc.perform(get("/failure/uncommitted-sse").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(result -> assertThat(result.getResolvedException()).isExactlyInstanceOf(IOException.class));
        assertServerFailureLogged(IOException.class);
    }

    @Test
    void ordinaryIOExceptionStillReturnsAndLogsA500ServerError() throws Exception {
        mvc.perform(get("/failure/io").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(50000));
        assertServerFailureLogged(IOException.class);
    }

    @Test
    void ordinaryRuntimeFailureIsNotTreatedAsAStreamDisconnect() throws Exception {
        mvc.perform(get("/failure/runtime").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000));
        assertServerFailureLogged(IllegalStateException.class);
    }

    @Test
    void committedNonSseIOExceptionKeepsTheServerErrorHandlerAndItsStack() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("already sent non-SSE bytes");
        response.flushBuffer();
        // A real committed HTTP status cannot be changed on the wire. The handler
        // must nevertheless retain its 500 result and ERROR record for this case.
        var error = new GlobalExceptionHandler().handleIoException(new IOException("synthetic non-SSE fault"), response);
        assertThat(error).isNotNull();
        assertThat(error.getStatusCode().value()).isEqualTo(500);
        assertThat(error.getBody().code()).isEqualTo(50000);
        assertThat(response.getContentAsString()).isEqualTo("already sent non-SSE bytes");
        assertServerFailureLogged(IOException.class);
    }

    @Test
    void springAsyncDisconnectRetainsItsMoreSpecificExistingHandler() throws Exception {
        mvc.perform(get("/failure/spring-async-disconnect"))
                .andExpect(content().string(""))
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isExactlyInstanceOf(AsyncRequestNotUsableException.class));
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private void assertServerFailureLogged(Class<? extends Exception> type) {
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("unhandled exception");
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(type.getName());
        });
    }

    @RestController
    static class FailureController {
        @GetMapping("/failure/committed-sse")
        void committedSse(HttpServletResponse response) throws IOException {
            response.setContentType("text/event-stream;charset=UTF-8");
            response.getWriter().write(SENT_EVENT);
            response.flushBuffer();
            throw new IOException("你的主机中的软件中止了一个已建立的连接。");
        }

        @GetMapping("/failure/uncommitted-sse")
        void uncommittedSse(HttpServletResponse response) throws IOException {
            response.setContentType("text/event-stream;charset=UTF-8");
            throw new IOException("synthetic pre-stream server fault");
        }

        @GetMapping("/failure/io")
        void io() throws IOException {
            throw new IOException("synthetic server IO fault");
        }

        @GetMapping("/failure/runtime")
        void runtime() {
            throw new IllegalStateException("synthetic server runtime fault", new IOException("synthetic nested IO cause"));
        }

        @GetMapping("/failure/spring-async-disconnect")
        void springAsyncDisconnect() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("synthetic already unusable response");
        }
    }
}
