package com.aiflow.engine.api;

import com.aiflow.engine.api.service.WorkflowService;
import com.aiflow.engine.graph.EdgeDef;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
 * 额度的端到端用例：一轮对话把「正常 -> 软阈值降档 -> 用尽拦截」三档全走一遍。
 *
 * <p>桩云端每次报 11 + 7 = 18 token，额度设成 20：
 * <ul>
 *   <li>第一轮：额度正常，走云端；扣减后 18/20 = 90% => 软阈值。</li>
 *   <li>第二轮：软阈值，路由优先便宜档位（mock）—— 断言云端调用次数不涨，证明降档真的发生了。</li>
 *   <li>第三轮：mock 的用量把账推过 20 => 用尽，返回预设话术且不再调用任何模型。</li>
 * </ul>
 * 最后再用一个新用户跑一轮，证明额度是按用户隔离的，不是全局开关。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BudgetIntegrationTest {

    private static final String EXHAUSTED_REPLY = "今日 AI 用量已达上限，请明天再试或联系管理员。";

    private static final AtomicInteger CLOUD_CALLS = new AtomicInteger();
    private static final HttpServer CLOUD_STUB = startCloudStub();

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterAll
    static void stopStub() {
        CLOUD_STUB.stop(0);
    }

    @DynamicPropertySource
    static void budgetProperties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.provider.mode", () -> "auto");
        registry.add("aiflow.provider.order", () -> "cloud,mock");
        // 本机可能真的装着 Ollama：关掉 local 端点，让「便宜档位」只剩 mock，用例才是确定的
        registry.add("aiflow.provider.endpoints.local.enabled", () -> "false");
        registry.add("aiflow.provider.endpoints.cloud.enabled", () -> "true");
        registry.add("aiflow.provider.endpoints.cloud.base-url",
                () -> "http://127.0.0.1:" + CLOUD_STUB.getAddress().getPort() + "/v1");
        registry.add("aiflow.provider.endpoints.cloud.model", () -> "budget-stub");
        registry.add("aiflow.provider.endpoints.cloud.capabilities", () -> "chat");
        registry.add("aiflow.provider.endpoints.cloud.tier", () -> "cloud");
        registry.add("aiflow.provider.probe.enabled", () -> "false");
        registry.add("aiflow.budget.per-user-daily-tokens", () -> "20");
        registry.add("aiflow.budget.soft-threshold-ratio", () -> "0.8");
        registry.add("aiflow.budget.store", () -> "memory");
    }

    @Test
    void degradesToCheapTierThenStopsSpendingWhenTheQuotaRunsOut() throws Exception {
        String workflowName = registerChatWorkflow();
        UUID userId = createUser();
        UUID sessionId = createSession(userId, workflowName);
        // 用例之间没有顺序约定，云端调用次数一律用相对值断言
        int callsBefore = CLOUD_CALLS.get();

        JsonNode first = awaitRun(send(sessionId, "第一轮"));
        assertThat(first.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(first.path("output").path("reply").asText())
                .as("桩云端的答复文本 = 真的走了云端（mock 的文本里带「（mock）」前缀）")
                .isEqualTo("云端回答");
        assertThat(first.path("output").path("tokensUsed").asInt()).isEqualTo(18);
        assertThat(first.path("output").path("budgetStatus").asText()).isEqualTo("SOFT");
        assertThat(CLOUD_CALLS.get()).isEqualTo(callsBefore + 1);

        JsonNode budget = getData("/api/v1/budget/users/" + userId);
        assertThat(budget.path("used").asLong()).isEqualTo(18);
        assertThat(budget.path("limit").asLong()).isEqualTo(20);
        assertThat(budget.path("status").asText()).isEqualTo("SOFT");
        assertThat(budget.path("store").asText()).isEqualTo("memory");

        JsonNode second = awaitRun(send(sessionId, "第二轮"));
        assertThat(second.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(second.path("output").path("reply").asText())
                .as("软阈值后优先便宜档位：mock 顶上，云端不再被打")
                .startsWith("（mock）");
        assertThat(CLOUD_CALLS.get()).isEqualTo(callsBefore + 1);

        long usedAfterSoft = getData("/api/v1/budget/users/" + userId).path("used").asLong();
        assertThat(usedAfterSoft).isGreaterThan(20);
        assertThat(getData("/api/v1/budget/users/" + userId).path("status").asText()).isEqualTo("EXHAUSTED");

        JsonNode third = awaitRun(send(sessionId, "第三轮"));
        assertThat(third.path("status").asText()).as("额度用尽是产品行为，不是故障：DEGRADED 而不是 FAILED")
                .isEqualTo("DEGRADED");
        assertThat(third.path("output").path("reply").asText()).isEqualTo(EXHAUSTED_REPLY);
        assertThat(third.path("output").path("budgetBlocked").asBoolean()).isTrue();
        assertThat(third.path("output").path("tokensUsed").isMissingNode())
                .as("拦截发生在调用之前：这一轮没有任何 token 消耗记录")
                .isTrue();
        assertThat(CLOUD_CALLS.get()).isEqualTo(callsBefore + 1);
        assertThat(getData("/api/v1/budget/users/" + userId).path("used").asLong())
                .as("被拦截的这一轮不产生任何扣减")
                .isEqualTo(usedAfterSoft);
    }

    @Test
    void keepsQuotasSeparatePerUser() throws Exception {
        String workflowName = registerChatWorkflow();
        UUID userId = createUser();
        UUID sessionId = createSession(userId, workflowName);
        int callsBefore = CLOUD_CALLS.get();

        JsonNode run = awaitRun(send(sessionId, "你好"));

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("output").path("reply").asText())
                .as("额度是新用户自己的：别人用尽不该影响它，它照样能走云端")
                .isEqualTo("云端回答");
        assertThat(CLOUD_CALLS.get()).isEqualTo(callsBefore + 1);
    }

    /** 桩模型：每次报 11 + 7 token 的用量，让「扣减用的是 provider 上报值」可断言。 */
    private static HttpServer startCloudStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                CLOUD_CALLS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 200, "text/event-stream",
                        "data: {\"model\":\"budget-stub\",\"choices\":[{\"delta\":{\"content\":\"云端回答\"}}]}\n\n"
                                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}\n\n"
                                + "data: [DONE]\n\n");
            });
            server.createContext("/v1/models", exchange -> respond(exchange, 200, "application/json", "{}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private UUID send(UUID sessionId, String content) throws Exception {
        return UUID.fromString(postData("/api/v1/sessions/" + sessionId + "/messages",
                Map.of("clientMsgId", "budget-it-" + UUID.randomUUID(), "content", content))
                .path("runId").asText());
    }

    private String registerChatWorkflow() {
        String name = "budget-it-" + UUID.randomUUID();
        workflowService.register(new WorkflowDefinition(UUID.randomUUID().toString(), name, 1,
                List.of(new NodeDef("start", "start", "开始", Map.of(), Set.of(), null, null),
                        new NodeDef("llm", "llm.chat", "对话", Map.of("prompt", "${input}"), Set.of(), null, null),
                        new NodeDef("end", "end", "结束",
                                Map.of("outputs", Map.of("reply", "reply", "fallback", "fallback",
                                        "budgetStatus", "budgetStatus", "budgetUsed", "budgetUsed",
                                        "budgetLimit", "budgetLimit", "budgetBlocked", "budgetBlocked",
                                        "tokensUsed", "tokensUsed")),
                                Set.of(), null, null)),
                List.of(new EdgeDef("start", "llm", null), new EdgeDef("llm", "end", null))));
        return name;
    }

    private UUID createUser() throws Exception {
        return UUID.fromString(postData("/api/v1/users",
                        Map.of("externalId", "budget-it-" + UUID.randomUUID(), "displayName", "Budget IT"))
                .path("id").asText());
    }

    private UUID createSession(UUID userId, String workflowName) throws Exception {
        return UUID.fromString(postData("/api/v1/sessions",
                        Map.of("userId", userId.toString(), "title", "额度集成", "workflowName", workflowName))
                .path("id").asText());
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

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}