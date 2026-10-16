package com.aiflow.engine.api;

import com.aiflow.engine.api.service.WorkflowService;
import com.aiflow.engine.graph.EdgeDef;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.persistence.entity.UserEntity;
import com.aiflow.engine.persistence.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Instant;
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
 * 工具粒度授权 + 审计的端到端验证（H2 + 桩 HTTP 工具，全程离线）。
 *
 * <p>三件事一起验：**放行**的用户能拿到结果、**未授权**的用户在节点处被挡住（且没有真的发出请求）、
 * 两种结果都留下了可查询的审计记录。第二条尤其重要 —— "被策略拒绝"必须在**调用之前**发生，
 * 否则拒绝对外部系统来说已经太晚了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ToolAuthorizationIntegrationTest {

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer STUB = startStub();

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.tools.enabled", () -> "true");
        registry.add("aiflow.tools.allow-hosts", () -> "127.0.0.1");
        registry.add("aiflow.tools.allow-private-hosts", () -> "127.0.0.1");
        registry.add("aiflow.tools.allow-ports", () -> String.valueOf(STUB.getAddress().getPort()));
        registry.add("aiflow.tools.registry.it-auth-tool.enabled", () -> "true");
        registry.add("aiflow.tools.registry.it-auth-tool.method", () -> "GET");
        registry.add("aiflow.tools.registry.it-auth-tool.url",
                () -> "http://127.0.0.1:" + STUB.getAddress().getPort() + "/resource");
        // 只有 alice 能用这个工具；其余人一律被策略挡住（default-allow 保持 true，让规则本身承担拒绝）
        registry.add("aiflow.tools.policy.rules[0].tool", () -> "it-auth-tool");
        registry.add("aiflow.tools.policy.rules[0].allow-users[0]", () -> ALLOWED_USER.toString());
        registry.add("aiflow.tools.policy.rules[0].reason", () -> "该工具只对特定用户开放");
    }

    private static final UUID ALLOWED_USER = UUID.randomUUID();

    private static final UUID DENIED_USER = UUID.randomUUID();

    /**
     * 用户 id 由测试直接落库：策略里写的是**确定的** userId，而创建用户接口只生成随机 id，
     * 两者对不上就没法验证"名单里的能用、名单外的被挡"。这里用仓储建两个已知 id 的用户。
     */
    @BeforeEach
    void seedUsers() {
        userRepository.save(new UserEntity(ALLOWED_USER, "auth-allowed-" + ALLOWED_USER, "允许的用户", Instant.now()));
        userRepository.save(new UserEntity(DENIED_USER, "auth-denied-" + DENIED_USER, "被挡的用户", Instant.now()));
    }

    @Test
    void allowedUserRunsToolAndDeniedUserIsBlockedBeforeCalling() throws Exception {
        String workflowName = registerWorkflow();
        int callsBefore = CALLS.get();

        // 1) 被授权的用户：正常拿到结果
        UUID allowedRun = runFor(workflowName, ALLOWED_USER);
        assertThat(runStatus(allowedRun)).isEqualTo("SUCCEEDED");
        assertThat(CALLS.get()).isEqualTo(callsBefore + 1);

        // 2) 未授权的用户：节点失败在授权检查处，桩服务不会收到请求
        UUID deniedUser = DENIED_USER;
        UUID deniedRun = runFor(workflowName, deniedUser);
        JsonNode denied = getData("/api/v1/runs/" + deniedRun);
        assertThat(denied.path("status").asText()).isEqualTo("FAILED");
        assertThat(denied.path("error").asText()).contains("工具未授权").contains("该工具只对特定用户开放");
        assertThat(CALLS.get()).isEqualTo(callsBefore + 1);

        // 3) 审计：两条记录都在，且口径不同（SUCCESS / DENIED）。
        //    默认读的是**落库**那份（store=database）—— 写库是异步的，所以要轮询等它落库；
        //    这本身就是「审计不阻塞工具调用」的证据：调用早就返回了，落库随后发生。
        JsonNode audit = awaitPersistedAudit("it-auth-tool");
        assertThat(audit.path("enabled").asBoolean()).isTrue();
        assertThat(audit.path("store").asText()).isEqualTo("database");
        assertThat(audit.path("persistent").asBoolean()).isTrue();
        assertThat(audit.path("failedWriteBatches").asLong()).isZero();
        List<String> outcomes = new java.util.ArrayList<>();
        audit.path("events").forEach(event -> outcomes.add(event.path("outcome").asText()));
        assertThat(outcomes).contains("SUCCESS", "DENIED");
        assertThat(audit.path("events").get(0).path("argsHash").asText()).isNotBlank();
        // 结果过滤同样走库。DENIED 只应有一条：策略拒绝是确定性失败，引擎不对它做重试
        // （重试的话这里会是两条 —— 审计记的是尝试，重复的「被拒」会把排障带偏）
        JsonNode deniedOnly = getData("/api/v1/tools/audit?toolId=it-auth-tool&outcome=DENIED&limit=20");
        List<String> deniedOutcomes = new java.util.ArrayList<>();
        deniedOnly.path("events").forEach(event -> deniedOutcomes.add(event.path("outcome").asText()));
        assertThat(deniedOutcomes).containsExactly("DENIED");

        // 4) 授权试算接口：不产生调用就能回答"他能不能用"
        JsonNode verdictForDenied = getData("/api/v1/tools/policy/evaluate?toolId=it-auth-tool&userId=" + deniedUser);
        assertThat(verdictForDenied.path("allowed").asBoolean()).isFalse();
        assertThat(verdictForDenied.path("registered").asBoolean()).isTrue();
        assertThat(verdictForDenied.path("rule").asText()).contains("it-auth-tool");

        JsonNode policy = getData("/api/v1/tools/policy");
        assertThat(policy.path("defaultAllow").asBoolean()).isTrue();
        assertThat(policy.path("rules").get(0).path("tool").asText()).isEqualTo("it-auth-tool");
    }

    /** 审计写入是异步的：轮询等两条记录都出现在库里（上限 10s，超时即失败而不是静默放过）。 */
    private JsonNode awaitPersistedAudit(String toolId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getData("/api/v1/tools/audit?toolId=" + toolId + "&limit=20");
            if (last.path("count").asInt() >= 2 && "database".equals(last.path("store").asText())) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("审计记录未在 10s 内落库: " + (last == null ? "null" : last.toString()));
    }

    private String registerWorkflow() throws Exception {
        String workflowName = "tool-auth-it-" + UUID.randomUUID();
        workflowService.register(new WorkflowDefinition(UUID.randomUUID().toString(), workflowName, 1,
                List.of(new NodeDef("start", "start", "开始", Map.of(), Set.of(), null, null),
                        new NodeDef("fetch", "tool.call", "调用工具", Map.of("tool", "it-auth-tool"),
                                Set.of(), null, null),
                        new NodeDef("end", "end", "结束",
                                Map.of("outputs", Map.of("body", "toolResult")), Set.of(), null, null)),
                List.of(new EdgeDef("start", "fetch", null), new EdgeDef("fetch", "end", null))));
        return workflowName;
    }

    /** 指定 userId 建会话并发一条消息（会话的 userId 就是工具授权的依据）。 */
    private UUID runFor(String workflowName, UUID userId) throws Exception {
        JsonNode session = postData("/api/v1/sessions",
                Map.of("userId", userId.toString(), "title", "tool 授权", "workflowName", workflowName));
        JsonNode accepted = postData("/api/v1/sessions/" + session.path("id").asText() + "/messages",
                Map.of("clientMsgId", "auth-it-" + UUID.randomUUID(), "content", "调用一次工具"));
        return awaitRunDone(UUID.fromString(accepted.path("runId").asText()));
    }

    private String runStatus(UUID runId) throws Exception {
        return getData("/api/v1/runs/" + runId).path("status").asText();
    }

    private UUID awaitRunDone(UUID runId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode run = getData("/api/v1/runs/" + runId);
            String status = run.path("status").asText();
            if (!"PENDING".equals(status) && !"RUNNING".equals(status)) {
                return runId;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("运行未在 20s 内结束: " + runId);
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/resource", exchange -> {
                CALLS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] payload = "{\"source\":\"auth-stub\"}".getBytes(StandardCharsets.UTF_8);
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
