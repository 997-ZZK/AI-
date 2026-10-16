package com.aiflow.engine.api;

import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.provider.CapabilityRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能力档位状态：现在能干什么、用的是哪一档、哪些档位挂了。控制台与压测脚本都读它。
 */
@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilityController {

    private final CapabilityRegistry registry;

    public CapabilityController(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ApiResponse<CapabilityRegistry.CapabilityReport> capabilities() {
        return ApiResponse.ok(registry.report());
    }
}