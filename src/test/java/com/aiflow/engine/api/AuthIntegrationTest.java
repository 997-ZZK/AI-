package com.aiflow.engine.api;

import com.aiflow.engine.approval.ApprovalPolicy;
import com.aiflow.engine.approval.ApprovalService;
import com.aiflow.engine.approval.ApprovalStatus;
import com.aiflow.engine.approval.RejectAction;
import com.aiflow.engine.approval.TimeoutAction;
import com.aiflow.engine.persistence.repo.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权最小切片的端到端口径（H2 + MockMvc，鉴权在本上下文里**开着**）。
 *
 * <p>钉住五条最容易退化的语义：
 * ① 无令牌 -> 401，但 {@code /whoami} 匿名也 200（排障要能问「你认出我了没」）；
 * ② 令牌不认 -> **401 而不是匿名放行**（「没带」和「不认」必须分得开）；
 * ③ 令牌有效但不在名单 -> 403，且审批仍是 PENDING（名单外批不动，也**不消耗**这次审批）；
 * ④ 自报与令牌不一致 -> 400（不能默默忽略，否则调用方以为自报生效了）；
 * ⑤ 身份在场时**以令牌为准**：body 省略 decidedBy 也能批，落库的是令牌身份。
 *
 * <p>鉴权**关闭**时的兼容口径由 {@link AuthDisabledIntegrationTest} 显式钉住
 * （{@code IdentityContextTest} 管 resolveActor 的单元语义，那边管「注解在关闭态不拦人」）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    private static final String TOKEN_A = "test-token-reviewer-a";
    private static final String TOKEN_B = "test-token-reviewer-b";

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("aiflow.auth.enabled", () -> "true");
        registry.add("aiflow.auth.subjects[0].id", () -> "reviewer-a");
        registry.add("aiflow.auth.subjects[0].display-name", () -> "审批人 A");
        registry.add("aiflow.auth.subjects[0].token-env", () -> "AIFLOW_TEST_AUTH_TOKEN_REVIEWER_A");
        registry.add("aiflow.auth.subjects[1].id", () -> "reviewer-b");
        registry.add("aiflow.auth.subjects[1].token-env", () -> "AIFLOW_TEST_AUTH_TOKEN_REVIEWER_B");
        registry.add("AIFLOW_TEST_AUTH_TOKEN_REVIEWER_A", () -> TOKEN_A);
        registry.add("AIFLOW_TEST_AUTH_TOKEN_REVIEWER_B", () -> TOKEN_B);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Test
    void whoamiIsAnonymousWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/whoami"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.authenticated").value(false))
                .andExpect(jsonPath("$.data.subject").value(nullValue()));
    }

    @Test
    void whoamiReportsTheSubjectBehindTheToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/whoami").header(HttpHeaders.AUTHORIZATION, bearer(TOKEN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.subject.id").value("reviewer-a"))
                .andExpect(jsonPath("$.data.subject.displayName").value("审批人 A"));
    }

    @Test
    void decisionWithoutTokenIsUnauthorized() throws Exception {
        UUID approvalId = pendingApproval();

        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"decidedBy\":\"reviewer-a\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        assertThat(storedStatus(approvalId)).as("被拦在业务逻辑之前，审批不受影响").isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void unknownTokenIsUnauthorizedRatherThanAnonymous() throws Exception {
        UUID approvalId = pendingApproval();

        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer("not-a-real-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"decidedBy\":\"reviewer-a\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value(containsString("令牌")));
    }

    @Test
    void nonAssigneeTokenIsForbiddenAndDoesNotConsumeTheApproval() throws Exception {
        UUID approvalId = pendingApproval();

        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TOKEN_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        assertThat(storedStatus(approvalId)).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void conflictingSelfReportIsBadRequest() throws Exception {
        UUID approvalId = pendingApproval();

        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TOKEN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"decidedBy\":\"reviewer-b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("令牌身份")));

        assertThat(storedStatus(approvalId)).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void decisionUsesTokenIdentityAndBodyMayOmitDecidedBy() throws Exception {
        UUID approvalId = pendingApproval();

        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TOKEN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"鉴权用例\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.decidedBy").value("reviewer-a"));

        assertThat(approvalRepository.findById(approvalId).orElseThrow().getDecidedBy())
                .as("落库的决策人必须是令牌身份，不是自报字段")
                .isEqualTo("reviewer-a");
    }

    @Test
    void cancelAlsoRequiresIdentity() throws Exception {
        UUID runId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/runs/{id}/cancel", runId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        // 带上令牌就放行到业务层：运行不存在 -> 409（这一步同时证明 401 是身份关卡，不是「什么都没做」
        mockMvc.perform(post("/api/v1/runs/{id}/cancel", runId).header(HttpHeaders.AUTHORIZATION, bearer(TOKEN_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    @Test
    void conflictingActorOnCancelIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/runs/{id}/cancel", UUID.randomUUID())
                        .param("actor", "reviewer-b")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TOKEN_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("令牌身份")));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private ApprovalStatus storedStatus(UUID approvalId) {
        return approvalRepository.findById(approvalId).orElseThrow().getStatus();
    }

    /** 造一条等审批的待办：直接用审批服务落库，不跑工作流（鉴权要验的是接口层，不是引擎）。 */
    private UUID pendingApproval() {
        return approvalService.createRequest(UUID.randomUUID(), "approve", 1,
                "鉴权用例：等审批", "detail", List.of("reviewer-a"),
                new ApprovalPolicy(TimeoutAction.REJECT, 1, TimeoutAction.REJECT, List.of(),
                        RejectAction.FALLBACK, "驳回话术", 3600),
                Instant.now().plusSeconds(3600)).getId();
    }
}
