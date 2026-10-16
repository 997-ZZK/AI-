package com.aiflow.engine.api;

import com.aiflow.engine.api.service.WorkflowService;
import com.aiflow.engine.graph.EdgeDef;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.persistence.entity.NodeRunEntity;
import com.aiflow.engine.persistence.entity.UserEntity;
import com.aiflow.engine.persistence.repo.NodeRunRepository;
import com.aiflow.engine.persistence.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 人工审批的端到端验证（H2 + MockMvc，全程离线）。
 *
 * <p>钉住四条最容易在重构里悄悄退化的语义：
 * ① 挂起后恢复**不重放**已执行节点（重放等于工具重复出网、模型重复扣费）；
 * ② 审批决策走条件更新，**第二个赢家拿 409**（多实例下不能一次审批触发两次恢复）；
 * ③ 名单外的人批不动（403 且**不消耗**这次决策）；
 * ④ 挂起期间**不持会话锁**（同会话的下一条消息能照常跑完 —— 有意的取舍，见 docs/approval-node-design.md §2）。
 *
 * <p>超时与取消各一条：超时不需要人也能收口；取消必须**同时**关掉运行与待办，之后决策拿 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalFlowIntegrationTest {

    private static final UUID USER = UUID.randomUUID();

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NodeRunRepository nodeRunRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 扫描周期调快：超时用例要等自动收口，默认 5s 一轮会把用例拖到十几秒。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.approval.sweep-ms", () -> "200");
    }

    @BeforeEach
    void seedUser() {
        if (userRepository.findById(USER).isEmpty()) {
            userRepository.save(new UserEntity(USER, "approval-it-" + USER, "审批用例用户", Instant.now()));
        }
    }

    @Test
    void approveResumesRunWithoutReplayingExecutedNodes() throws Exception {
        String assignee = uniqueAssignee();
        String workflowName = registerWorkflow(assignee, 600, "reject", "fallback", "未通过时的兜底话术");
        RunRef run = startRun(workflowName, "我要报销 1000 元");
        awaitStatus(run.runId(), "WAITING_APPROVAL");

        JsonNode todo = awaitPendingTodo(assignee);
        assertThat(todo.path("status").asText()).isEqualTo("PENDING");
        assertThat(todo.path("title").asText()).as("标题里的 ${input} 要被渲染").contains("我要报销 1000 元");
        assertThat(todo.path("dueAt").asText()).isNotBlank();
        assertThat(todo.path("assignees").get(0).asText()).isEqualTo(assignee);

        List<NodeRunEntity> waiting = nodeRunRepository.findByRunIdOrderByStartedAtAsc(run.runId());
        NodeRunEntity approvalRow = lastRow(waiting, "approve");
        assertThat(approvalRow.getStatus()).isEqualTo(NodeRunStatus.WAITING);
        assertThat(approvalRow.getEndedAt()).as("挂起中的节点行不能有结束时间").isNull();
        long startsBeforeResume = countRows(waiting, "start");

        JsonNode decided = decide(UUID.fromString(todo.path("approvalId").asText()), "APPROVE", assignee, 200);
        assertThat(decided.path("status").asText()).isEqualTo("APPROVED");
        assertThat(decided.path("decidedBy").asText()).isEqualTo(assignee);

        JsonNode finished = awaitStatus(run.runId(), "SUCCEEDED");
        assertThat(finished.path("output").path("approvalStatus").asText()).isEqualTo("APPROVED");
        assertThat(finished.path("output").path("approvalBy").asText()).isEqualTo(assignee);

        List<NodeRunEntity> after = nodeRunRepository.findByRunIdOrderByStartedAtAsc(run.runId());
        assertThat(countRows(after, "start")).as("恢复不能重放已执行节点").isEqualTo(startsBeforeResume);
        assertThat(lastRow(after, "approve").getStatus()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(lastRow(after, "approve").getEndedAt()).as("恢复后断点行才收口").isNotNull();
        assertThat(lastRow(after, "end").getStatus()).isEqualTo(NodeRunStatus.SUCCEEDED);

        JsonNode chain = getData("/api/v1/runs/" + run.runId() + "/approvals");
        assertThat(chain.size()).isEqualTo(1);
        assertThat(chain.get(0).path("status").asText()).isEqualTo("APPROVED");

        decide(UUID.fromString(todo.path("approvalId").asText()), "APPROVE", assignee, 409);
    }

    @Test
    void rejectTurnsRunIntoDegradedWithConfiguredFallback() throws Exception {
        String assignee = uniqueAssignee();
        String fallback = "很抱歉，本次申请未通过审批。";
        String workflowName = registerWorkflow(assignee, 600, "reject", "fallback", fallback);
        RunRef run = startRun(workflowName, "申请采购两台显示器");
        awaitStatus(run.runId(), "WAITING_APPROVAL");

        JsonNode todo = awaitPendingTodo(assignee);
        decide(UUID.fromString(todo.path("approvalId").asText()), "REJECT", assignee, 200);

        JsonNode finished = awaitStatus(run.runId(), "DEGRADED");
        assertThat(finished.path("output").path("approvalStatus").asText()).isEqualTo("REJECTED");
        assertThat(finished.path("output").path("fallback").asText())
                .as("拒绝要走节点上配的那句兜底话术，不是全局默认").isEqualTo(fallback);
        NodeRunEntity approvalRow = lastRow(nodeRunRepository.findByRunIdOrderByStartedAtAsc(run.runId()), "approve");
        assertThat(approvalRow.getStatus()).isEqualTo(NodeRunStatus.DEGRADED);
        assertThat(approvalRow.getError()).contains("拒绝");
    }

    @Test
    void strangerCannotDecideAndTheDecisionIsNotConsumed() throws Exception {
        String assignee = uniqueAssignee();
        String workflowName = registerWorkflow(assignee, 600, "reject", "fallback", "兜底");
        RunRef run = startRun(workflowName, "申请开通生产库只读账号");
        awaitStatus(run.runId(), "WAITING_APPROVAL");
        JsonNode todo = awaitPendingTodo(assignee);
        UUID approvalId = UUID.fromString(todo.path("approvalId").asText());

        decide(approvalId, "APPROVE", "someone-else", 403);
        assertThat(awaitStatus(run.runId(), "WAITING_APPROVAL").path("status").asText())
                .as("403 之后运行必须还在等，不能被这次无效决策推走").isEqualTo("WAITING_APPROVAL");
        assertThat(getData("/api/v1/approvals/" + approvalId).path("approval").path("status").asText())
                .isEqualTo("PENDING");

        decide(approvalId, "APPROVE", assignee, 200);
        awaitStatus(run.runId(), "SUCCEEDED");
    }

    @Test
    void cancelWhileWaitingClosesBothRunAndTodo() throws Exception {
        String assignee = uniqueAssignee();
        String workflowName = registerWorkflow(assignee, 600, "reject", "fallback", "兜底");
        RunRef run = startRun(workflowName, "申请删除三个月前的日志");
        awaitStatus(run.runId(), "WAITING_APPROVAL");
        JsonNode todo = awaitPendingTodo(assignee);
        UUID approvalId = UUID.fromString(todo.path("approvalId").asText());

        JsonNode canceled = postData("/api/v1/runs/" + run.runId() + "/cancel?actor=ops");
        assertThat(canceled.path("status").asText()).isEqualTo("CANCELED");
        assertThat(awaitStatus(run.runId(), "CANCELED").path("status").asText()).isEqualTo("CANCELED");

        JsonNode chain = getData("/api/v1/runs/" + run.runId() + "/approvals");
        assertThat(chain.get(0).path("status").asText()).isEqualTo("CANCELED");
        NodeRunEntity canceledRow = lastRow(nodeRunRepository.findByRunIdOrderByStartedAtAsc(run.runId()), "approve");
        assertThat(canceledRow.getStatus()).as("取消后断点节点行不能还挂着 WAITING").isEqualTo(NodeRunStatus.FAILED);
        assertThat(canceledRow.getEndedAt()).isNotNull();
        assertThat(canceledRow.getError()).contains("取消");
        assertThat(getData("/api/v1/approvals?assignee=" + assignee).path("count").asInt())
                .as("待办必须随着取消一起消失").isZero();
        decide(approvalId, "APPROVE", assignee, 409);
    }

    @Test
    void timeoutRejectsAndResumesWithoutAnyHuman() throws Exception {
        String assignee = uniqueAssignee();
        String fallback = "审批超时未处理，已自动打回。";
        String workflowName = registerWorkflow(assignee, 1, "reject", "fallback", fallback);
        RunRef run = startRun(workflowName, "申请临时调高接口限流");
        awaitStatus(run.runId(), "WAITING_APPROVAL");

        JsonNode finished = awaitStatus(run.runId(), "DEGRADED");
        assertThat(finished.path("output").path("fallback").asText()).isEqualTo(fallback);

        JsonNode chain = getData("/api/v1/runs/" + run.runId() + "/approvals");
        assertThat(chain.get(0).path("status").asText()).isEqualTo("REJECTED");
        assertThat(chain.get(0).path("decidedBy").asText()).isEqualTo("system");
        assertThat(chain.get(0).path("comment").asText()).contains("超时");

        JsonNode detail = getData("/api/v1/approvals/" + chain.get(0).path("approvalId").asText());
        assertThat(detail.path("events")).anySatisfy(event ->
                assertThat(event.path("event").asText()).isEqualTo("REJECTED"));
    }

    @Test
    void suspendedRunDoesNotBlockItsSession() throws Exception {
        String assignee = uniqueAssignee();
        String workflowName = registerWorkflow(assignee, 600, "reject", "fallback", "兜底");
        RunRef first = startRun(workflowName, "第一条：需要人批的申请");
        awaitStatus(first.runId(), "WAITING_APPROVAL");

        UUID secondRunId = sendMessage(first.sessionId(), "第二条：同会话的另一条申请");
        awaitStatus(secondRunId, "WAITING_APPROVAL");

        JsonNode todo = awaitRunApproval(first.runId());
        decide(UUID.fromString(todo.path("approvalId").asText()), "APPROVE", assignee, 200);
        awaitStatus(first.runId(), "SUCCEEDED");
        assertThat(awaitStatus(secondRunId, "WAITING_APPROVAL").path("status").asText())
                .as("恢复第一条不能把第二条也推进").isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void timeoutEscalatesToNextLevelAndTheBossCanStillApprove() throws Exception {
        String firstAssignee = uniqueAssignee();
        String bossAssignee = uniqueAssignee();
        String workflowName = registerWorkflow(firstAssignee, 5, "escalate", "fallback", "兜底", 2, bossAssignee);
        RunRef run = startRun(workflowName, "申请临时提升数据库权限");
        awaitStatus(run.runId(), "WAITING_APPROVAL");

        JsonNode escalated = awaitEscalation(run.runId());
        assertThat(escalated.path("level").asInt()).isEqualTo(2);
        assertThat(escalated.path("status").asText()).isEqualTo("PENDING");
        assertThat(escalated.path("assignees").get(0).asText()).isEqualTo(bossAssignee);
        assertThat(escalated.path("title").asText()).contains("申请临时提升数据库权限");

        decide(UUID.fromString(escalated.path("approvalId").asText()), "APPROVE", bossAssignee, 200);
        assertThat(awaitStatus(run.runId(), "SUCCEEDED").path("output").path("approvalStatus").asText())
                .isEqualTo("APPROVED");

        JsonNode chain = getData("/api/v1/runs/" + run.runId() + "/approvals");
        assertThat(chain.size()).as("升级要留痕：旧层 EXPIRED + 新层 APPROVED").isEqualTo(2);
        assertThat(chain.get(0).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(chain.get(0).path("decidedBy").asText()).isEqualTo("system");
        assertThat(chain.get(1).path("status").asText()).isEqualTo("APPROVED");
        assertThat(chain.get(1).path("decidedBy").asText()).isEqualTo(bossAssignee);
    }

    @Test
    void rejectWithFailActionFailsTheRunInsteadOfFallingBack() throws Exception {
        String assignee = uniqueAssignee();
        String workflowName = registerWorkflow(assignee, 600, "reject", "fail", "这句兜底不该被用到");
        RunRef run = startRun(workflowName, "申请导出全量用户数据");
        awaitStatus(run.runId(), "WAITING_APPROVAL");

        JsonNode todo = awaitPendingTodo(assignee);
        decide(UUID.fromString(todo.path("approvalId").asText()), "REJECT", assignee, 200);

        JsonNode finished = awaitStatus(run.runId(), "FAILED");
        assertThat(finished.path("error").asText()).as("失败原因要带上审批意见").contains("拒绝");
        assertThat(finished.path("output").path("approvalStatus").asText()).isEqualTo("REJECTED");
        assertThat(finished.path("output").path("fallback").isMissingNode())
                .as("onReject=fail 是失败语义，不该再塞兜底话术").isTrue();
        assertThat(lastRow(nodeRunRepository.findByRunIdOrderByStartedAtAsc(run.runId()), "approve").getStatus())
                .isEqualTo(NodeRunStatus.FAILED);
    }

    @Test
    void timeoutApproveLetsTheRunThroughWithoutAnyHuman() throws Exception {
        String assignee = uniqueAssignee();
        String workflowName = registerWorkflow(assignee, 1, "approve", "fallback", "兜底");
        RunRef run = startRun(workflowName, "低风险变更：申请夜间发布窗口");
        awaitStatus(run.runId(), "WAITING_APPROVAL");

        assertThat(awaitStatus(run.runId(), "SUCCEEDED").path("output").path("approvalStatus").asText())
                .isEqualTo("APPROVED");
        JsonNode chain = getData("/api/v1/runs/" + run.runId() + "/approvals");
        assertThat(chain.get(0).path("status").asText()).isEqualTo("APPROVED");
        assertThat(chain.get(0).path("decidedBy").asText()).isEqualTo("system");
        assertThat(chain.get(0).path("comment").asText()).contains("超时");
    }

    @Test
    void escalationAtTopFallsBackToExhaustedAction() throws Exception {
        String assignee = uniqueAssignee();
        String fallback = "升级到顶仍无人处理，已按策略打回。";
        String workflowName = registerWorkflow(assignee, 1, "escalate", "fallback", fallback, 2,
                uniqueAssignee());
        RunRef run = startRun(workflowName, "无人值守的越权申请");
        awaitStatus(run.runId(), "WAITING_APPROVAL");

        JsonNode finished = awaitStatus(run.runId(), "DEGRADED");
        assertThat(finished.path("output").path("fallback").asText())
                .as("到顶之后按 onExhausted 收口，兜底话术来自同一个策略快照").isEqualTo(fallback);
        JsonNode chain = getData("/api/v1/runs/" + run.runId() + "/approvals");
        assertThat(chain.size()).isEqualTo(2);
        assertThat(chain.get(0).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(chain.get(1).path("status").asText()).as("到顶不能再升，按 onExhausted=reject 打回")
                .isEqualTo("REJECTED");
    }

    /** 等升级把第二层待办挂出来（第一层到点后由扫描器收口并建新层）。 */
    private JsonNode awaitEscalation(UUID runId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getData("/api/v1/runs/" + runId + "/approvals");
            if (last.size() >= 2) {
                return last.get(1);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("审批未在 20s 内升级到第二层: " + (last == null ? "null" : last));
    }

    private String registerWorkflow(String assignee, int waitSeconds, String onTimeout, String onReject,
                                    String fallback) {
        return registerWorkflow(assignee, waitSeconds, onTimeout, onReject, fallback, 1, null);
    }

    /** maxLevel / escalateAssignees 只有升级用例需要，其余用例走上面的 5 参版本（默认 1 层、不升级）。 */
    private String registerWorkflow(String assignee, int waitSeconds, String onTimeout, String onReject,
                                    String fallback, int maxLevel, String escalateAssignees) {
        String name = "approval-it-" + UUID.randomUUID();
        Map<String, Object> approvalParams = new LinkedHashMap<>();
        approvalParams.put("title", "审批申请：${input}");
        approvalParams.put("detail", "提交人：${input}");
        approvalParams.put("assignees", assignee);
        approvalParams.put("waitSeconds", waitSeconds);
        approvalParams.put("onTimeout", onTimeout);
        approvalParams.put("maxLevel", maxLevel);
        if (escalateAssignees != null) {
            approvalParams.put("escalateAssignees", List.of(escalateAssignees));
        }
        approvalParams.put("onExhausted", "reject");
        approvalParams.put("onReject", onReject);
        approvalParams.put("fallback", fallback);
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

    private RunRef startRun(String workflowName, String content) throws Exception {
        JsonNode session = postData("/api/v1/sessions",
                Map.of("userId", USER.toString(), "title", "审批用例", "workflowName", workflowName));
        UUID sessionId = UUID.fromString(session.path("id").asText());
        return new RunRef(sessionId, sendMessage(sessionId, content));
    }

    private UUID sendMessage(UUID sessionId, String content) throws Exception {
        JsonNode accepted = postData("/api/v1/sessions/" + sessionId + "/messages",
                Map.of("clientMsgId", "approval-it-" + UUID.randomUUID(), "content", content));
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

    /** 同一会话里可能同时挂着几条待办（挂起不持锁的后果之一）：按运行查审批链，才不会拿错单子。 */
    private JsonNode awaitRunApproval(UUID runId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getData("/api/v1/runs/" + runId + "/approvals");
            if (last.size() >= 1) {
                return last.get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("运行未在 20s 内挂出审批单: " + runId + " / " + (last == null ? "null" : last));
    }

    private JsonNode decide(UUID approvalId, String decision, String actor, int expectedHttp) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("decision", decision, "decidedBy", actor, "comment", "集成用例的审批意见"));
        String response = mockMvc.perform(post("/api/v1/approvals/" + approvalId + "/decision")
                        .contentType("application/json").content(body))
                .andExpect(status().is(expectedHttp))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(response);
        if (expectedHttp == 200) {
            assertThat(json.path("code").asInt()).isZero();
            return json.path("data");
        }
        assertThat(json.path("code").asInt()).as("失败响应必须带业务错误码").isNotZero();
        return json;
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
        return "reviewer-" + UUID.randomUUID();
    }

    private static long countRows(List<NodeRunEntity> rows, String nodeId) {
        return rows.stream().filter(row -> nodeId.equals(row.getNodeId())).count();
    }

    /** 同一节点重试/恢复会有多行：取最后一行才是「这一行现在怎么样」。 */
    private static NodeRunEntity lastRow(List<NodeRunEntity> rows, String nodeId) {
        return rows.stream().filter(row -> nodeId.equals(row.getNodeId())).reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("没有节点行: " + nodeId));
    }

    private record RunRef(UUID sessionId, UUID runId) {
    }
}
