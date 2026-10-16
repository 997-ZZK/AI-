package com.aiflow.engine.node.builtin;

import com.aiflow.engine.approval.ApprovalPolicy;
import com.aiflow.engine.approval.ApprovalProperties;
import com.aiflow.engine.approval.ApprovalService;
import com.aiflow.engine.approval.RejectAction;
import com.aiflow.engine.approval.TimeoutAction;
import com.aiflow.engine.approval.notify.ApprovalNotifyDispatcher;
import com.aiflow.engine.approval.notify.ApprovalNotifyProperties;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.graph.GraphValidationException;
import com.aiflow.engine.graph.GraphValidator;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.graph.RetryPolicy;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.node.NodeRegistry;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.NodeRunStatus;
import com.aiflow.engine.persistence.entity.ApprovalEntity;
import com.aiflow.engine.persistence.repo.ApprovalEventRepository;
import com.aiflow.engine.persistence.repo.ApprovalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批节点的装配期校验与挂起输出。
 *
 * <p>参数配错的审批节点必须在**注册工作流时**就被拒：审批是「运行到一半才发现少配了审批人」
 * 代价最高的地方 —— 那意味着一个已经挂起的运行永远没人能批。
 *
 * <p>不需要 Spring：ApprovalService 用 Mockito 桩替换仓储，只验「落了一条什么待办」。
 */
class ApprovalNodeExecutorTest {

    private final ApprovalProperties properties = ApprovalProperties.defaults();

    @Test
    void rejectsNodeWithoutAssigneesAtRegistrationTime() {
        assertThat(problems(node(Map.of()))).anySatisfy(problem ->
                assertThat(problem).contains("assignees"));
    }

    @Test
    void rejectsRetryOnApprovalNode() {
        NodeDef node = node(Map.of("assignees", "reviewer-a"), new RetryPolicy(3, 100));

        assertThat(problems(node)).anySatisfy(problem -> assertThat(problem).contains("重试"));
    }

    @Test
    void rejectsEscalateWithoutRoomToEscalate() {
        assertThat(problems(node(Map.of("assignees", "reviewer-a", "onTimeout", "escalate", "maxLevel", 1))))
                .anySatisfy(problem -> assertThat(problem).contains("maxLevel"));
    }

    @Test
    void rejectsEscalateAsExhaustedAction() {
        assertThat(problems(node(Map.of("assignees", "reviewer-a", "onExhausted", "escalate"))))
                .anySatisfy(problem -> assertThat(problem).contains("onExhausted"));
    }

    @Test
    void rejectsWaitSecondsAboveDeploymentCap() {
        assertThat(problems(node(Map.of("assignees", "reviewer-a", "waitSeconds", 99_999_999))))
                .anySatisfy(problem -> assertThat(problem).contains("上限"));
    }

    @Test
    void acceptsWellConfiguredNode() {
        assertThat(problems(node(Map.of("assignees", "reviewer-a, reviewer-b",
                "onTimeout", "escalate", "maxLevel", 2, "escalateAssignees", List.of("boss"),
                "waitSeconds", 120)))).isEmpty();
    }

    @Test
    void suspendsWithPendingApprovalAndPolicySnapshot() {
        ApprovalService approvalService = approvalService();
        ApprovalNodeExecutor executor = new ApprovalNodeExecutor(approvalService, properties);

        NodeResult result = execute(executor, node(Map.of("assignees", "reviewer-a, reviewer-b",
                "title", "审批退款 ${amount}", "onTimeout", "escalate", "maxLevel", 2,
                "escalateAssignees", List.of("boss"), "onReject", "fail", "fallback", "未通过",
                "waitSeconds", 300)));

        assertThat(result.status()).isEqualTo(NodeRunStatus.WAITING);
        assertThat(result.output()).containsEntry("approvalStatus", "PENDING");
        assertThat(result.output()).containsEntry("approvalAssignees", "reviewer-a,reviewer-b");
        assertThat(String.valueOf(result.output().get("approvalDueAt"))).isNotBlank();
        assertThat(String.valueOf(result.output().get("approvalId"))).isNotBlank();

        ApprovalEntity saved = capturedApproval(approvalService);
        assertThat(saved.getStatus().name()).isEqualTo("PENDING");
        assertThat(saved.getLevel()).isEqualTo(1);
        assertThat(saved.getTitle()).as("标题里的 ${amount} 要被变量渲染").isEqualTo("审批退款 1000");
        ApprovalPolicy policy = approvalService.policyOf(saved);
        assertThat(policy.onTimeout()).isEqualTo(TimeoutAction.ESCALATE);
        assertThat(policy.maxLevel()).isEqualTo(2);
        assertThat(policy.escalateAssignees()).containsExactly("boss");
        assertThat(policy.onReject()).isEqualTo(RejectAction.FAIL);
        assertThat(policy.fallback()).isEqualTo("未通过");
        assertThat(policy.waitSeconds()).isEqualTo(300);
    }

    private NodeResult execute(ApprovalNodeExecutor executor, NodeDef node) {
        return executor.execute(new com.aiflow.engine.node.NodeContext(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), node, new java.util.LinkedHashMap<>(Map.of("amount", "1000")),
                new InMemoryRunEventBus(EngineProperties.defaults()), () -> false));
    }

    private ApprovalEntity capturedApproval(ApprovalService service) {
        ArgumentCaptor<ApprovalEntity> captor = ArgumentCaptor.forClass(ApprovalEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private ApprovalRepository repository;

    private ApprovalService approvalService() {
        repository = mock(ApprovalRepository.class);
        ApprovalEventRepository events = mock(ApprovalEventRepository.class);
        when(repository.save(org.mockito.ArgumentMatchers.any(ApprovalEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // 通知在没有渠道时连线程都不起，所以这里给空渠道清单：单测不该因为旁路投递引入线程
        return new ApprovalService(repository, events, new InMemoryRunEventBus(EngineProperties.defaults()),
                new ObjectMapper(), properties,
                new ApprovalNotifyDispatcher(List.of(), ApprovalNotifyProperties.defaults()));
    }

    /** 走一遍真实的 GraphValidator：校验必须发生在注册期，而不是运行期。 */
    private List<String> problems(NodeDef node) {
        ApprovalService approvalService = approvalService();
        // start / end 也注册上：校验器会检查每个节点的类型是否注册，只注册审批节点会把
        // 「start 未注册」这类噪声混进断言里（接受用例要求 problems 为空，噪声会直接让它失败）
        NodeRegistry registry = new NodeRegistry(List.of(new StartNodeExecutor(), new EndNodeExecutor(),
                new ApprovalNodeExecutor(approvalService, properties)));
        WorkflowDefinition definition = new WorkflowDefinition("wf", "approval-test", 1,
                List.of(new NodeDef("start", "start", "start", Map.of(), Set.of(), null, null), node,
                        new NodeDef("end", "end", "end", Map.of(), Set.of(), null, null)),
                List.of(new com.aiflow.engine.graph.EdgeDef("start", node.id(), null),
                        new com.aiflow.engine.graph.EdgeDef(node.id(), "end", null)));
        try {
            new GraphValidator(registry).validate(definition);
            return List.of();
        } catch (GraphValidationException e) {
            return e.getProblems();
        }
    }

    private NodeDef node(Map<String, Object> params) {
        return node(params, null);
    }

    private NodeDef node(Map<String, Object> params, RetryPolicy retry) {
        return new NodeDef("approve", "approval", "人工审批", params, Set.of(), retry, null);
    }
}
