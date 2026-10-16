package com.aiflow.engine.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配：**开了但没凭证 = 没开 + WARN**。
 *
 * <p>为什么不做成"凭证缺失就启动失败"：原件留存是可选的旁路能力，缺凭证时让整个引擎起不来，
 * 代价远大于收益。但也不能静默 —— 启动日志里必须能看到"你开了，但它没生效，原因是 X"。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public ObjectStorage objectStorage(StorageProperties properties) {
        if (!properties.enabled()) {
            log.info("原件留存未开启（aiflow.storage.enabled=false）：入库只留切片，不存原件");
            return new DisabledObjectStorage("原件留存未开启（aiflow.storage.enabled=false）");
        }
        if (!properties.credentialsPresent()) {
            log.warn("原件留存已开启但缺少凭证（AIFLOW_STORAGE_ACCESS_KEY / AIFLOW_STORAGE_SECRET_KEY）：按未开启处理");
            return new DisabledObjectStorage("原件留存已开启但未提供凭证（access-key / secret-key 只从环境变量来）");
        }
        log.info("原件留存已开启: {}", properties.describe());
        return new MinioObjectStorage(properties);
    }
}
