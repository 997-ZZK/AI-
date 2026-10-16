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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权**关闭**时的兼容口径（H2 + MockMvc，本上下文里 {@code aiflow.auth.enabled=false}）。
 *
 * <p>为什么要单独钉一份：{@code @RequireIdentity} 的第一版是静态注解，关着鉴权也拦人，
 * 把存量用例一次性打成了 401。所以「关着的时候整个切片不生效」必须是**显式断言**，
 * 不能靠「默认上下文恰好没开鉴权」隐式覆盖。这里钉四条：
 * ① {@code /whoami} 如实回答 enabled=false；
 * ② 不带令牌 + 自报 decidedBy -&gt; 200，落库的就是自报的人（本地零配置开发照常可跑）；
 * ③ 自报的人不在名单里 -&gt; 仍然 403 且不消耗审批（业务层「谁能批」不因鉴权关闭而消失）；
 * ④ 带了乱令牌 -&gt; 也被忽略（「关」= 整个切片不参与，不是「半开」）。
 *
 * <p>开启态语义见 {@link AuthIntegrationTest}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthDisabledIntegrationTest {

    @DynamicPropertySource
    static void authIsOff(DynamicPropertyRegistry registry) {
        registry.add("aiflow.auth.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Test
    void whoamiReportsAuthIsOff() throws Exception {
        mockMvc.perform(get("/api/v1/auth/whoami"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.authenticated").value(false))
                .andExpect(jsonPath("$.data.subject").value(nullValue()));
    }

    @Test
    void decisionWithoutTokenUsesTheSelfReportedActor() throws Exception {
        UUID approvalId = pendingApproval("reviewer-a");

        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"decidedBy\":\"reviewer-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.decidedBy").value("reviewer-a"));

        assertThat(approvalRepository.findById(approvalId).orElseThrow().getDecidedBy())
                .as("鉴权关闭时 decidedBy 就是自报字段（启动 WARN 已明说它不是安全边界）")
                .isEqualTo("reviewer-a");
    }

    @Test
    void businessRuleStillRejectsActorsOutsideTheAssigneeList() throws Exception {
        UUID approvalId = pendingApproval("reviewer-a");

        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"decidedBy\":\"outsider\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .as("被拒的这次不能消耗审批")
                .isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void bogusBearerIsIgnoredWhileAuthIsOff() throws Exception {
        UUID approvalId = pendingApproval("reviewer-a");

        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"decidedBy\":\"reviewer-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decidedBy").value("reviewer-a"));
    }

    private UUID pendingApproval(String assignee) {
        return approvalService.createRequest(UUID.randomUUID(), "approve", 1,
                "鉴权关闭用例：等审批", "detail", List.of(assignee),
                new ApprovalPolicy(TimeoutAction.REJECT, 1, TimeoutAction.REJECT, List.of(),
                        RejectAction.FALLBACK, "驳回话术", 3600),
                Instant.now().plusSeconds(3600)).getId();
    }
}
