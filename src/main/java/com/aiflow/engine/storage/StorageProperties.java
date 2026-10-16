package com.aiflow.engine.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 对象存储配置（原件留存）。
 *
 * <p>默认**关**。理由和 OCR 一样：这是一条"能力档位"——不开也能完整跑通入库与检索，
 * 只是回溯时"只有切片没有原件"。开了之后失败也不阻断入库（见 DocumentIngestService）。
 *
 * <p>凭证只从环境变量来（access-key / secret-key 的默认值是空串）：写进配置文件就等于进了仓库，
 * 和工具侧的 auth-token-env、MCP 的 oauth 是同一条纪律。
 *
 * @param enabled   是否开启原件留存
 * @param endpoint  S3 兼容端点（MinIO 默认 http://localhost:19000）
 * @param accessKey 访问密钥（从环境变量注入）
 * @param secretKey 访问密钥（从环境变量注入）
 * @param bucket    桶名（不存在会自动创建）
 * @param timeout   每个对象存储 HTTP call 的连接、读写与总时限（含流式响应体读取）
 */
@ConfigurationProperties(prefix = "aiflow.storage")
public record StorageProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:19000") String endpoint,
        @DefaultValue("") String accessKey,
        @DefaultValue("") String secretKey,
        @DefaultValue("aiflow-originals") String bucket,
        @DefaultValue("10s") Duration timeout
) {

    public boolean credentialsPresent() {
        return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    /** 诊断用：不含凭证。 */
    public String describe() {
        return endpoint + "/" + bucket;
    }
}
