package com.aiflow.engine.bootstrap;

import com.aiflow.engine.api.service.WorkflowService;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.graph.WorkflowDefinition;
import com.aiflow.engine.persistence.repo.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * demo 工作流的注册判重：按「名字 + 版本」，不是「名字存在就跳过」。
 *
 * <p>后者会让 {@code workflows/*.json} 在第一次启动之后彻底变成摆设：改参数不生效，而且没人看得出来。
 * 现在的约定是「改 JSON 就升 version，重启即生效」，这条约定由测试守住。
 */
@SpringBootTest
class DemoDataInitializerTest {

    private static final String PROBE = "it-probe";

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResourceLoader resourceLoader;

    @Test
    void registersTheNewVersionEvenWhenAnOlderOneIsAlreadyStored() throws Exception {
        WorkflowDefinition probe = load("workflows/" + PROBE + ".json");
        workflowService.register(new WorkflowDefinition(probe.id(), probe.name(), 1, probe.nodes(), probe.edges()));
        assertThat(latestVersion()).isEqualTo(1);

        initializer().run(null);

        assertThat(latestVersion()).as("库里已有 v1 不影响 v2 的注册").isEqualTo(2);
    }

    @Test
    void reRunningRegistersNothingTwice() throws Exception {
        DemoDataInitializer initializer = initializer();
        initializer.run(null);
        long rowsAfterFirstRun = storedRows();

        initializer.run(null);

        assertThat(storedRows()).as("每次启动都插一遍会在库里堆重复工作流").isEqualTo(rowsAfterFirstRun);
    }

    private DemoDataInitializer initializer() {
        EngineProperties defaults = EngineProperties.defaults();
        EngineProperties probeOnly = new EngineProperties(defaults.maxSteps(), defaults.defaultNodeTimeoutSeconds(),
                defaults.defaultMaxAttempts(), defaults.defaultBackoffMillis(), defaults.sessionQueueCapacity(),
                defaults.sseTimeoutMs(), defaults.sseHeartbeatMs(), defaults.defaultWorkflow(),
                new String[]{PROBE}, defaults.fallbackReply(), defaults.eventBufferSize(), defaults.session());
        return new DemoDataInitializer(workflowRepository, workflowService, objectMapper, resourceLoader, probeOnly);
    }

    private int latestVersion() {
        return workflowRepository.findFirstByNameOrderByVersionDesc(PROBE).orElseThrow().getVersion();
    }

    private long storedRows() {
        return workflowRepository.findAll().stream().filter(entity -> PROBE.equals(entity.getName())).count();
    }

    private WorkflowDefinition load(String resourcePath) throws IOException {
        Resource resource = resourceLoader.getResource("classpath:" + resourcePath);
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, WorkflowDefinition.class);
        }
    }
}