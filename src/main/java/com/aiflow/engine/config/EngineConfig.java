package com.aiflow.engine.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.aiflow.engine.approval.ApprovalProperties;
import com.aiflow.engine.approval.notify.ApprovalNotifyProperties;
import com.aiflow.engine.common.auth.AuthProperties;
import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.longterm.LongMemoryProperties;
import com.aiflow.engine.common.auth.RequireIdentityInterceptor;
import com.aiflow.engine.tool.mcp.McpToolProperties;
import com.aiflow.engine.tool.skill.SkillToolProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({EngineProperties.class, ProviderProperties.class,
        AttachmentProperties.class, ToolProperties.class, McpToolProperties.class,
        SkillToolProperties.class, SafetyProperties.class, BudgetProperties.class, IngestProperties.class,
        ToolPolicyProperties.class, ToolAuditProperties.class, ApprovalProperties.class,
        ApprovalNotifyProperties.class, AuthProperties.class, MemoryProperties.class,
        LongMemoryProperties.class, DispatchProperties.class, AudioProperties.class})
public class EngineConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService nodeExecutorService() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * {@code @RequireIdentity} 的执行：拦截器挂在 /api/** 上，但只对带注解的接口要求身份，
     * 其余照常放行 —— 本地开发与控制台要零配置可用（见 docs/auth-design.md §3.3）。
     */
    @Bean
    public WebMvcConfigurer requireIdentityWebMvcConfigurer(RequireIdentityInterceptor requireIdentityInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(requireIdentityInterceptor).addPathPatterns("/api/**");
            }
        };
    }
}
