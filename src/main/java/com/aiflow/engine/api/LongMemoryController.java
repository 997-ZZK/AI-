package com.aiflow.engine.api;

import com.aiflow.engine.api.service.LongMemoryService;
import com.aiflow.engine.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 长期记忆的口径（设计见 docs/long-memory-design.md §3.5）。
 *
 * <p>四个接口解决四件事：**看见了什么**（preview —— 没有它，真实栈只能断言「没报错」）、
 * **现在有什么**（列表）、**手工纠正**（POST，人工优先）、**忘掉它**（DELETE，硬删）。
 *
 * <p>记忆正文属于用户自己的数据，这里不做脱敏；但事件流、日志与 SSE 里绝不写正文。
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/memories")
public class LongMemoryController {

    private final LongMemoryService longMemoryService;

    public LongMemoryController(LongMemoryService longMemoryService) {
        this.longMemoryService = longMemoryService;
    }

    @GetMapping
    public ApiResponse<List<LongMemoryService.MemoryView>> list(@PathVariable UUID userId,
                                                               @RequestParam(required = false) String kind,
                                                               @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(longMemoryService.list(userId, kind, limit));
    }

    /** 手工新增 / 纠正：来源记 MANUAL、置信度 1.00，此后抽取再也覆盖不了它。 */
    @PostMapping
    public ApiResponse<LongMemoryService.MemoryView> create(@PathVariable UUID userId,
                                                            @Valid @RequestBody CreateMemoryRequest request) {
        return ApiResponse.ok(longMemoryService.create(userId, request.kind(), request.key(), request.value()));
    }

    /** 硬删该条 + 同事务清掉它的事件行（用户说「删掉」就该删干净）。 */
    @DeleteMapping("/{memoryId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable UUID userId, @PathVariable UUID memoryId) {
        longMemoryService.delete(userId, memoryId);
        return ApiResponse.ok(Map.of("deleted", true, "memoryId", memoryId.toString()));
    }

    /** 把召回结果摊开：命中了哪几条、多少分、为什么被丢掉（阈值 / 条数 / 字符预算）。 */
    @GetMapping("/preview")
    public ApiResponse<LongMemoryService.RecallPreview> preview(@PathVariable UUID userId,
                                                               @RequestParam(required = false) String input,
                                                               @RequestParam(defaultValue = "0") int topK) {
        return ApiResponse.ok(longMemoryService.preview(userId, input, topK));
    }

    public record CreateMemoryRequest(@NotBlank String kind, @NotBlank String key, @NotBlank String value) {
    }
}
