package com.aiflow.engine.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一错误包装的边界：**调用方写错的请求必须是 4xx**，不能掉进兜底 handler 变成 500 + 一条 ERROR 栈。
 *
 * <p>两类都验：路径/资源写错（404）、报文与参数写错（400 / 405 / 415）。不修的话，
 * 控制台每刷新一次 favicon 就往日志里灌一条假故障；调用方会把「请求写错了」误判成服务端故障去重试。
 *
 * <p>其中「请求体不是合法 JSON」额外钉一条**不回显报文**：Jackson 的原始 message 里带着请求体的
 * 前几十个字符，那可能是用户的提示词或备注，不该出现在响应体与日志里。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorResponseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingStaticResourceIsNotFoundRatherThanServerError() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void unknownApiPathIsNotFoundRatherThanServerError() throws Exception {
        mockMvc.perform(get("/api/v1/kbs/demo/not-a-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void malformedRequestBodyIsBadRequestAndNeverEchoesThePayload() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/{id}/decision", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"decidedBy\":\"alice\",\"comment\":\"token-abc123\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(not(containsString("token-abc123"))));
    }

    @Test
    void missingRequestBodyIsBadRequestRatherThanServerError() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/{id}/decision", UUID.randomUUID())
                        .contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void wrongHttpMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/v1/approvals"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(40500))
                .andExpect(jsonPath("$.message").value(containsString("DELETE")));
    }

    @Test
    void unsupportedContentTypeIsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/{id}/decision", UUID.randomUUID())
                        .contentType("text/plain")
                        .content("decision=APPROVE"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(41500));
    }

    @Test
    void pathVariableTypeMismatchIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/approvals/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("approvalId")));
    }
}