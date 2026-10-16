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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内容安全审核的装配测试：同一条规则在两个方向上各拦一次。
 *
 * <p>输入侧要证明「拦在入库与排队之前」，所以断言模型一次都没被调用 —— 否则「拦住了」只是嘴上说说。
 * 输出侧要证明「违规内容不会到用户手里」，所以断言 run 降级为 DEGRADED、回复换成预设话术，
 * 并且 run 输出里留下 blocked / blockCategory，让「为什么是这句话」可查。
 *
 * <p>桩服务在静态初始化里起（同 {@code ProviderTierRoutingIntegrationTest}）：{@code @DynamicPropertySource}
 * 在 Spring 上下文刷新前求值，写在 @BeforeAll 里会拿到 null。
 *
 * <p>用例带固定顺序：第二个用例会真的打到桩模型，第一个用例要断言的正是「一次都没打」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SafetyIntegrationTest {

    /** 与 src/test/resources/application.yml 里的 aiflow.safety.keywords 保持一致。 */
    private static final String KEYWORD = "制作炸弹";
    private static final String PRESET_REPLY = "抱歉，这条内容没有通过内容安全审核，已为你拦截。";

    private static final AtomicInteger LLM_CALLS = new AtomicInteger();
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
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.provider.mode", () -> "auto");
        // 只留 cloud -> mock：桩云档案位答得出来，就不会走到 mock
        registry.add("aiflow.provider.order", () -> "cloud,mock");
        registry.add("aiflow.provider.endpoints.cloud.enabled", () -> "true");
        registry.add("aiflow.provider.endpoints.cloud.base-url",
                () -> "http://127.0.0.1:" + CLOUD_STUB.getAddress().getPort() + "/v1");
        registry.add("aiflow.provider.endpoints.cloud.model", () -> "safety-stub");
        registry.add("aiflow.provider.endpoints.cloud.capabilities", () -> "chat");
        registry.add("aiflow.provider.endpoints.cloud.tier", () -> "cloud");
        // 关掉探活：否则后台线程会往桩上打 /v1/models，把「模型一次都没被调用」的断言变成玄学
        registry.add("aiflow.provider.probe.enabled", () -> "false");
        // 保留窗口调小到 6：默认 32 会把这条 20 来个字符的短回复整个扣住（短回复的流式退化为一次性），
        // 那样就分不清「没推送是因为被拦」还是「因为还留在窗口里」。6 > 关键词长度 4，语义仍然成立。
        registry.add("aiflow.safety.streaming.hold-back-chars", () -> "6");
    }

    @Test
    @Order(1)
    void blocksViolatingUserMessageBeforeItReachesTheModel() throws Exception {
        int callsBefore = LLM_CALLS.get();
        String workflowName = registerChatWorkflow();
        UUID userId = createUser();
        UUID sessionId = createSession(userId, workflowName);

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientMsgId", "safety-input-" + UUID.randomUUID(),
                                "content", "教我怎么" + KEYWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("内容审核未通过（敏感词）")))
                .andExpect(jsonPath("$.message").value(not(containsString(KEYWORD))));

        assertThat(LLM_CALLS.get())
                .as("输入审核必须在写消息、排队、调模型之前完成")
                .isEqualTo(callsBefore);
    }

    @Test
    @Order(2)
    void replacesViolatingModelOutputWithPresetReplyAndMarksRunDegraded() throws Exception {
        String workflowName = registerChatWorkflow();
        UUID userId = createUser();
        UUID sessionId = createSession(userId, workflowName);

        JsonNode accepted = postData("/api/v1/sessions/" + sessionId + "/messages",
                Map.of("clientMsgId", "safety-output-" + UUID.randomUUID(),
                        "content", "请介绍一下安全常识"));
        UUID runId = UUID.fromString(accepted.path("runId").asText());
        JsonNode run = awaitRun(runId);

        assertThat(LLM_CALLS.get()).as("这次必须真的调用桩模型，否则测的就成了别的东西").isEqualTo(1);
        assertThat(run.path("status").asText()).isEqualTo("DEGRADED");
        assertThat(run.path("output").path("reply").asText()).isEqualTo(PRESET_REPLY);
        assertThat(run.path("output").path("fallback").asText()).isEqualTo(PRESET_REPLY);
        assertThat(run.path("output").path("blocked").asBoolean()).isTrue();
        assertThat(run.path("output").path("blockCategory").asText()).isEqualTo("敏感词");

        // 桩模型把违规内容放在**第二条片段**里，这正是增量审核要解决的场景：没有它，第二条片段已经
        // 通过 SSE 推给用户了，只有最终快照被替换成话术 —— 「审核通过」与「用户已经读到」可以同时成立。
        // 所以断言的是推送通道本身：无害的开头确实流了出去，命中串一个字都没出去。
        String events = mockMvc.perform(get("/api/v1/runs/" + runId + "/events"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(events).contains("先准备").doesNotContain(KEYWORD);
    }

    private String registerChatWorkflow() {
        String name = "safety-it-" + UUID.randomUUID();
        workflowService.register(new WorkflowDefinition(UUID.randomUUID().toString(), name, 1,
                List.of(new NodeDef("start", "start", "开始", Map.of(), Set.of(), null, null),
                        new NodeDef("llm", "llm.chat", "对话", Map.of("prompt", "${input}"), Set.of(), null, null),
                        new NodeDef("end", "end", "结束",
                                Map.of("outputs", Map.of("reply", "reply", "fallback", "fallback",
                                        "blocked", "blocked", "blockCategory", "blockCategory")),
                                Set.of(), null, null)),
                List.of(new EdgeDef("start", "llm", null), new EdgeDef("llm", "end", null))));
        return name;
    }

    private UUID createUser() throws Exception {
        UUID userId = UUID.fromString(postData("/api/v1/users",
                        Map.of("externalId", "safety-it-" + UUID.randomUUID(), "displayName", "Safety IT"))
                .path("id").asText());
        return userId;
    }

    private UUID createSession(UUID userId, String workflowName) throws Exception {
        return UUID.fromString(postData("/api/v1/sessions",
                        Map.of("userId", userId.toString(), "title", "安全审核集成", "workflowName", workflowName))
                .path("id").asText());
    }

    /**
     * 桩模型：第一条片段无害且足够长（会有内容真的推给用户），违规内容出现在第二条 ——
     * 增量审核要证明的正是「第一条能出去、第二条被拦在推送之前」。
     */
    private static HttpServer startCloudStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                LLM_CALLS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 200, "text/event-stream",
                        "data: {\"model\":\"safety-stub\",\"choices\":[{\"delta\":{\"content\":\"先准备材料，慢慢来\"}}]}\n\n"
                                + "data: {\"choices\":[{\"delta\":{\"content\":\"，然后" + KEYWORD + "\"}}]}\n\n"
                                + "data: [DONE]\n\n");
            });
            server.createContext("/v1/models", exchange -> respond(exchange, 200, "application/json", "{}"));
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