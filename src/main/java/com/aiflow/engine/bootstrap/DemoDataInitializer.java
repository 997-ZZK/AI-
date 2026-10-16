package com.aiflow.engine.bootstrap;

import com.aiflow.engine.api.service.WorkflowService;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.persistence.repo.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.IOException;

@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final EngineProperties properties;

    public DemoDataInitializer(WorkflowRepository workflowRepository, WorkflowService workflowService,
                               ObjectMapper objectMapper, ResourceLoader resourceLoader, EngineProperties properties) {
        this.workflowRepository = workflowRepository;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        for (String name : properties.demoWorkflows()) {
            registerIfMissing(name);
        }
    }

    /**
     * 按「名字 + 版本」判重：改了 {@code workflows/*.json} 就升 version，下次启动自动注册新版本，
     * 会话仍按最高版本解析（见 WorkflowService.resolveIdByName）。
     *
     * <p>原来只要该名字存在任意版本就跳过，结果是 JSON 文件在第一次启动之后就成了摆设 ——
     * 改参数不会生效，也没人看得出来。
     */
    private void registerIfMissing(String name) throws IOException {
        Resource resource = resourceLoader.getResource("classpath:workflows/" + name + ".json");
        if (!resource.exists()) {
            log.warn("demo workflow resource not found: {}", resource);
            return;
        }
        WorkflowDefinition definition;
        try (InputStream inputStream = resource.getInputStream()) {
            definition = objectMapper.readValue(inputStream, WorkflowDefinition.class);
        }
        if (workflowRepository.existsByNameAndVersion(definition.name(), definition.version())) {
            return;
        }
        workflowService.register(definition);
        log.info("registered demo workflow {} v{}", definition.name(), definition.version());
    }
}
