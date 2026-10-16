package com.aiflow.engine.api;

import com.aiflow.engine.api.service.SessionMemoryService;
import com.aiflow.engine.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 会话记忆的口径（设计见 docs/memory-design.md §3.5）。
 *
 * <p>三个接口解决三件事：**看见了什么**（preview —— 没有它，真实栈只能断言"没报错"）、
 * **现在什么状态**（status）、**重来一遍**（rebuild，换档位或改配置后用）。
 * 摘要正文属于用户自己的数据，这里不做脱敏；但日志与事件里绝不写正文。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/memory")
public class MemoryController {

    private final SessionMemoryService sessionMemoryService;

    public MemoryController(SessionMemoryService sessionMemoryService) {
        this.sessionMemoryService = sessionMemoryService;
    }

    @GetMapping
    public ApiResponse<SessionMemoryService.MemoryStatus> status(@PathVariable UUID sessionId) {
        return ApiResponse.ok(sessionMemoryService.status(sessionId));
    }

    @GetMapping("/preview")
    public ApiResponse<SessionMemoryService.MemoryPreview> preview(@PathVariable UUID sessionId,
                                                                   @RequestParam(required = false) String input) {
        return ApiResponse.ok(sessionMemoryService.preview(sessionId, input));
    }

    @PostMapping("/rebuild")
    public ApiResponse<SessionMemoryService.MemoryStatus> rebuild(@PathVariable UUID sessionId) {
        return ApiResponse.ok(sessionMemoryService.rebuild(sessionId));
    }
}
