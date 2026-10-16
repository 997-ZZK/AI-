package com.aiflow.engine.api;

import com.aiflow.engine.api.service.WorkflowService;
import com.aiflow.engine.approval.ApprovalService;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审批通知的端到端验证：状态机真的把消息投到了站在外面的接收方（H2 + 桩 HTTP + MockMvc，全程离线）。
 *
 * <p>钉住四件事：**投递发生在事务提交之后**（不然回滚过的状态也会被通知出去）、
 * 载荷里带得走一张待办卡片所需的一切、签名能被接收方独立算出来、以及
 * {@code GET /api/v1/approvals/notify} 能把「配了没生效 / 投丢了」暴露出来。
 *
 * <p>取消那条用例额外钉住一个容易漏的细节：批量更新后内存里的实体还是 PENDING，
 * 通知里的 status 必须显式纠正，否则接收方会收到「已取消」的标题配「待处理」的状态。
 *
 * <p>升级用例**自己驱动一轮超时扫描**，不靠后台调度器：测试用的 H2 是固定库名（jdbc:h2:mem:aiflow），
 * 同一 JVM 里所有 Spring 上下文共用它，别的上下文的 ApprovalTimeoutScheduler 也会扫到这张审批单 ——
 * 谁的条件更新先赢谁发通知，而那个上下文可能根本没配 webhook。靠调度器的用例会变成看运气，
 * 所以这里把 waitSeconds 设得足够长（别的上下文按真实时间扫不到），再显式传一个未来的 now 扫一轮。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalNotifyIntegrationTest {

    private static final UUID USER = UUID.randomUUID();
    private static final String SECRET = "it-approval-secret";
    private static final WebhookStub WEBHOOK = WebhookStub.start();

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.approval.sweep-ms", () -> "200");
        registry.add("aiflow.approval.notify.webhook.enabled", () -> "true");
        registry.add("aiflow.approval.notify.webhook.url",
                () -> "http://127.0.0.1:" + WEBHOOK.port() + "/approval");
        registry.add("aiflow.approval.notify.webhook.secret", () -> SECRET);
        // webhook 出网与 HTTP 工具共用同一份白名单：桩在回环地址上，必须显式放行
        registry.add("aiflow.tools.allow-hosts", () -> "127.0.0.1");
        registry.add("aiflow.tools.allow-private-hosts", () -> "127.0.0.1");
        registry.add("aiflow.tools.allow-ports", () -> String.valueOf(WEBHOOK.port()));
        registry.add("logging.level.com.aiflow.engine.approval.notify", () -> "DEBUG");
    }

    @AfterAll
    static void stopWebhook() {
        WEBHOOK.stop();
    }

    @BeforeEach
    void seedUser() {
        if (userRepository.findById(USER).isEmpty()) {
            userRepository.save(new UserEntity(USER, "approval-notify-it-" + USER, "审批通知用例用户", Instant.now()));
        }
    }

    @Test
    void requestedAndDecidedAreDelivered() throws Exception {
        String assignee = uniqueAssignee();
        String workflowName = registerWorkflow(assignee, 600, "reject", "fallback", 1, null);
        UUID runId = startRun(workflowName, "我要报销 1000 元");
        awaitStatus(runId, "WAITING_APPROVAL");
        JsonNode todo = awaitPendingTodo(assignee);
        UUID approvalId = UUID.fromString(todo.path("approvalId").asText());

        Recorded requested = WEBHOOK.await(approvalId.toString(), "REQUESTED");
        assertThat(requested).as("落待办必须通知出去").isNotNull();
        assertThat(requested.signature()).as("签名要能被接收方独立算出来")
                .isEqualTo("sha256=" + hmac(SECRET, requested.body()));
        JsonNode payload = objectMapper.readTree(requested.body());
        assertThat(payload.path("status").asText()).isEqualTo("PENDING");
        assertThat(payload.path("level").asInt()).isEqualTo(1);
        assertThat(payload.path("assignees").get(0).asText()).isEqualTo(assignee);
        assertThat(payload.path("title").asText()).as("载荷是事发那一刻的快照：模板已渲染").contains("我要报销 1000 元");
        assertThat(payload.path("runId").asText()).isEqualTo(runId.toString());

        decide(approvalId, "APPROVE", assignee, 200);
        Recorded decided = WEBHOOK.await(approvalId.toString(), "DECIDED");
        assertThat(decided).as("决策也要通知出去").isNotNull();
        JsonNode decidedPayload = objectMapper.readTree(decided.body());
        assertThat(decidedPayload.path("status").asText()).isEqualTo("APPROVED");
        assertThat(decidedPayload.path("decidedBy").asText()).isEqualTo(assignee);
        assertThat(decidedPayload.path("comment").asText()).contains("集成用例的审批意见");
        assertThat(decidedPayload.path("dedupeKey").asText()).isEqualTo(approvalId + "|DECIDED|1");

        JsonNode notify = getData("/api/v1/approvals/notify");
        assertThat(notify.path("enabled").asBoolean()).isTrue();
        assertThat(channel(notify, "log").path("enabled").asBoolean()).isTrue();
        JsonNode webhook = channel(notify, "webhook");
        assertThat(webhook.path("enabled").asBoolean()).as("桩地址在白名单里，必须就绪").isTrue();
        assertThat(webhook.path("target").asText()).contains("127.0.0.1:" + WEBHOOK.port());
        assertThat(webhook.path("signed").asBoolean()).isTrue();
        assertThat(webhook.path("blocked").isNull()).isTrue();
        assertThat(notify.path("sent").asLong()).as("至少 REQUESTED + DECIDED 两条投给了两个渠道").isGreaterThanOrEqualTo(4L);
        assertThat(notify.path("failed").asLong()).isZero();
        assertThat(notify.path("dropped").asLong()).isZero();
    }

    @Test
    void cancelIsDeliveredWithCanceledStatus() throws Exception {
        String assignee = uniqueAssignee();
        String workflowName = registerWorkflow(assignee, 600, "reject", "fallback", 1, null);
        UUID runId = startRun(workflowName, "申请删除三个月前的日志");
        awaitStatus(runId, "WAITING_APPROVAL");
        JsonNode todo = awaitPendingTodo(assignee);
        UUID approvalId = UUID.fromString(todo.path("approvalId").asText());
        assertThat(WEBHOOK.await(approvalId.toString(), "REQUESTED")).isNotNull();

        postData("/api/v1/runs/" + runId + "/cancel?actor=ops");

        Recorded canceled = WEBHOOK.await(approvalId.toString(), "CANCELED");
        assertThat(canceled).isNotNull();
        JsonNode payload = objectMapper.readTree(canceled.body());
        assertThat(payload.path("status").asText()).as("不能是 PENDING：那是批量更新留下的陈旧影子").isEqualTo("CANCELED");
        assertThat(payload.path("runId").asText()).isEqualTo(runId.toString());
    }

    @Test
    void escalateSendsExpiredWithTargetLevel() throws Exception {
        String assignee = uniqueAssignee();
        String escalateTo = uniqueAssignee();
        String workflowName = registerWorkflow(assignee, 3600, "escalate", "fallback", 2, escalateTo);
        UUID runId = startRun(workflowName, "申请临时调高接口限流");
        awaitStatus(runId, "WAITING_APPROVAL");
        JsonNode todo = awaitPendingTodo(assignee);
        UUID approvalId = UUID.fromString(todo.path("approvalId").asText());
        assertThat(WEBHOOK.await(approvalId.toString(), "REQUESTED")).isNotNull();

        // 决策窗口设成 1 小时（别的上下文按真实时间扫不到它），再由本上下文显式扫一轮
        approvalService.expireDue(Instant.now().plusSeconds(7200));

        Recorded expired = WEBHOOK.await(approvalId.toString(), "EXPIRED");
        assertThat(expired).as("超时升级要有通知，否则第一层的人以为单子还挂着").isNotNull();
        JsonNode payload = objectMapper.readTree(expired.body());
        assertThat(payload.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(payload.path("fromLevel").asInt()).isEqualTo(1);
        assertThat(payload.path("level").asInt()).as("EXPIRED 的 level 记升级后的目标层").isEqualTo(2);

        JsonNode second = awaitRunApproval(runId, 2);
        UUID secondId = UUID.fromString(second.path("approvalId").asText());
        Recorded escalated = WEBHOOK.await(secondId.toString(), "REQUESTED");
        assertThat(escalated).as("第二层要拿到自己的待办通知").isNotNull();
        JsonNode escalatedPayload = objectMapper.readTree(escalated.body());
        assertThat(escalatedPayload.path("level").asInt()).isEqualTo(2);
        assertThat(escalatedPayload.path("assignees").get(0).asText()).isEqualTo(escalateTo);
    }

    private static JsonNode channel(JsonNode notify, String name) {
        for (JsonNode node : notify.path("channels")) {
            if (name.equals(node.path("channel").asText())) {
                return node;
            }
        }
        throw new AssertionError("状态里没有渠道 " + name + ": " + notify);
    }

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String registerWorkflow(String assignee, int waitSeconds, String onTimeout, String onReject,
                                    int maxLevel, String escalateTo) {
        String name = "approval-notify-it-" + UUID.randomUUID();
        Map<String, Object> approvalParams = new LinkedHashMap<>();
        approvalParams.put("title", "审批申请：${input}");
        approvalParams.put("detail", "提交人：${input}");
        approvalParams.put("assignees", assignee);
        approvalParams.put("waitSeconds", waitSeconds);
        approvalParams.put("onTimeout", onTimeout);
        approvalParams.put("maxLevel", maxLevel);
        if (escalateTo != null) {
            approvalParams.put("escalateAssignees", List.of(escalateTo));
        }
        approvalParams.put("onExhausted", "reject");
        approvalParams.put("onReject", onReject);
        approvalParams.put("fallback", "兜底话术");
        workflowService.register(new WorkflowDefinition(UUID.randomUUID().toString(), name, 1,
                List.of(new NodeDef("start", "start", "开始", Map.of(), Set.of(), null, null),
                        new NodeDef("approve", "approval", "人工审批", approvalParams, Set.of(), null, null),
                        new NodeDef("end", "end", "结束", Map.of("outputs", Map.of(
                                "reply", "reply", "fallback", "fallback",
                                "approvalStatus", "approvalStatus", "approvalBy", "approvalBy")),
                                Set.of(), null, null)),
                List.of(new EdgeDef("start", "approve", null), new EdgeDef("approve", "end", null))));
        return name;
    }

    private UUID startRun(String workflowName, String content) throws Exception {
        JsonNode session = postData("/api/v1/sessions",
                Map.of("userId", USER.toString(), "title", "审批通知用例", "workflowName", workflowName));
        UUID sessionId = UUID.fromString(session.path("id").asText());
        JsonNode accepted = postData("/api/v1/sessions/" + sessionId + "/messages",
                Map.of("clientMsgId", "approval-notify-it-" + UUID.randomUUID(), "content", content));
        return UUID.fromString(accepted.path("runId").asText());
    }

    private JsonNode awaitStatus(UUID runId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getData("/api/v1/runs/" + runId);
            if (expected.equals(last.path("status").asText())) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("运行未在 20s 内变为 " + expected + "，最后状态: "
                + (last == null ? "null" : last.path("status").asText()) + " / " + runId);
    }

    private JsonNode awaitPendingTodo(String assignee) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getData("/api/v1/approvals?assignee=" + assignee);
            if (last.path("count").asInt() == 1) {
                return last.path("approvals").get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("待办未在 20s 内出现: " + assignee + " / " + (last == null ? "null" : last));
    }

    /** 升级后的第二层：同一次运行的第 n 张审批单。 */
    private JsonNode awaitRunApproval(UUID runId, int expectedCount) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getData("/api/v1/runs/" + runId + "/approvals");
            if (last.size() >= expectedCount) {
                return last.get(expectedCount - 1);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("运行未在 20s 内挂出第 " + expectedCount + " 层审批: " + runId
                + " / " + (last == null ? "null" : last.toString()));
    }

    private JsonNode decide(UUID approvalId, String decision, String actor, int expectedHttp) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("decision", decision, "decidedBy", actor, "comment", "集成用例的审批意见"));
        String response = mockMvc.perform(post("/api/v1/approvals/" + approvalId + "/decision")
                        .contentType("application/json").content(body))
                .andExpect(status().is(expectedHttp))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return unwrap(response);
    }

    private JsonNode getData(String path) throws Exception {
        return unwrap(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postData(String path) throws Exception {
        return unwrap(mockMvc.perform(post(path)).andExpect(status().isOk()).andReturn()
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

    private static String uniqueAssignee() {
        return "notify-reviewer-" + UUID.randomUUID();
    }

    private record Recorded(Map<String, String> headers, String body) {

        private String header(String name) {
            return headers.getOrDefault(name, "");
        }

        private String signature() {
            return header("x-aiflow-signature");
        }
    }

    /** 接收方：记下每一次投递，按 (approvalId, event) 取用 —— 用例之间共用一条队列，不能盲取队首。 */
    private static final class WebhookStub {

        private final HttpServer server;
        private final BlockingQueue<Recorded> recorded = new LinkedBlockingQueue<>();

        private WebhookStub(HttpServer server) {
            this.server = server;
        }

        private static WebhookStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                WebhookStub stub = new WebhookStub(server);
                server.createContext("/approval", exchange -> {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    Map<String, String> headers = new LinkedHashMap<>();
                    exchange.getRequestHeaders().forEach((name, values) ->
                            headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
                    stub.recorded.add(new Recorded(headers, new String(body, StandardCharsets.UTF_8)));
                    byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
                server.start();
                return stub;
            } catch (IOException e) {
                throw new IllegalStateException("桩 webhook 起不来", e);
            }
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private void stop() {
            server.stop(0);
        }

        /** 等到某张审批单的某个事件到达；到点没来返回 null（断言里给更清楚的失败信息）。 */
        private Recorded await(String approvalId, String event) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            List<Recorded> skipped = new ArrayList<>();
            try {
                while (System.currentTimeMillis() < deadline) {
                    Recorded next = recorded.poll(200, TimeUnit.MILLISECONDS);
                    if (next == null) {
                        continue;
                    }
                    if (approvalId.equals(next.header("x-aiflow-approval-id"))
                            && event.equals(next.header("x-aiflow-event"))) {
                        return next;
                    }
                    skipped.add(next);
                }
                System.out.println("[stub-webhook] await 超时: want=" + approvalId + "/" + event + " 队列剩余="
                        + recorded.size() + " 本次跳过=" + skipped.stream()
                        .map(r -> r.header("x-aiflow-event") + "@" + r.header("x-aiflow-approval-id")).toList());
                return null;
            } finally {
                recorded.addAll(skipped);
            }
        }
    }
}
