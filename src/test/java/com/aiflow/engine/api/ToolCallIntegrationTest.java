package com.aiflow.engine.api;

import com.aiflow.engine.api.service.WorkflowService;
import com.aiflow.engine.graph.EdgeDef;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工具调用的装配测试：配置声明的工具 -> 白名单校验 -> 节点 -> 运行快照，全链路走一遍。
 *
 * <p>桩服务在静态初始化里起：{@code @DynamicPropertySource} 在 Spring 上下文刷新前求值，
 * 放 @BeforeAll 会拿到 null（同 ProviderTierRoutingIntegrationTest 的处理）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ToolCallIntegrationTest {

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer STUB = startStub();

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @DynamicPropertySource
    static void toolProperties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.tools.enabled", () -> "true");
        registry.add("aiflow.tools.allow-hosts", () -> "127.0.0.1");
        registry.add("aiflow.tools.allow-private-hosts", () -> "127.0.0.1");
        registry.add("aiflow.tools.allow-ports", () -> String.valueOf(STUB.getAddress().getPort()));
        registry.add("aiflow.tools.registry.it-tool.enabled", () -> "true");
        registry.add("aiflow.tools.registry.it-tool.method", () -> "GET");
        registry.add("aiflow.tools.registry.it-tool.url",
                () -> "http://127.0.0.1:" + STUB.getAddress().getPort() + "/resource");
    }

    @Test
    void workflowRunsToolCallAndFeedsBodyToOutput() throws Exception {
        String workflowName = "tool-it-" + UUID.randomUUID();
        workflowService.register(new WorkflowDefinition(UUID.randomUUID().toString(), workflowName, 1,
                List.of(new NodeDef("start", "start", "开始", Map.of(), Set.of(), null, null),
                        new NodeDef("fetch", "tool.call", "调用工具", Map.of("tool", "it-tool"), Set.of(), null, null),
                        new NodeDef("end", "end", "结束",
                                Map.of("outputs", Map.of("toolBody", "toolResult", "toolStatus", "toolStatus")),
                                Set.of(), null, null)),
                List.of(new EdgeDef("start", "fetch", null), new EdgeDef("fetch", "end", null))));

        UUID userId = UUID.fromString(postData("/api/v1/users",
                Map.of("externalId", "tool-it-" + UUID.randomUUID(), "displayName", "Tool IT"))
                .path("id").asText());
        UUID sessionId = UUID.fromString(postData("/api/v1/sessions",
                        Map.of("userId", userId.toString(), "title", "tool 集成", "workflowName", workflowName))
                .path("id").asText());

        JsonNode accepted = postData("/api/v1/sessions/" + sessionId + "/messages",
                Map.of("clientMsgId", "tool-it-" + UUID.randomUUID(), "content", "调用一次工具"));
        JsonNode run = awaitRun(UUID.fromString(accepted.path("runId").asText()));

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("output").path("toolStatus").asInt()).isEqualTo(200);
        assertThat(run.path("output").path("toolBody").asText()).contains("stub-payload");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/resource", exchange -> {
                CALLS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] payload = "{\"source\":\"stub-payload\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode awaitRun(UUID runId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode run = null;
        while (System.currentTimeMillis() < deadline) {
            run = getData("/api/v1/runs/" + runId);
            String runStatus = run.path("status").asText();
            if (!"PENDING".equals(runStatus) && !"RUNNING".equals(runStatus)) {
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("运行未在 20s 内结束: " + run);
    }

    private JsonNode getData(String path) throws Exception {
        return unwrap(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postData(String path, Map<String, Object> body) throws Exception {
        return unwrap(mockMvc.perform(post(path)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode unwrap(String body) throws Exception {
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data");
    }
}