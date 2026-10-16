package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 附件（图片）存储配置。
 *
 * @param dir            落盘目录，相对路径按进程工作目录解析
 * @param maxBytes       单文件上限；超出直接拒绝，不做截断
 * @param maxPerRequest  单次请求最多引用几张图片（多模态模型的 payload 与费用都随张数涨）
 */
@ConfigurationProperties(prefix = "aiflow.attachments")
public record AttachmentProperties(
        @DefaultValue("data/attachments") String dir,
        @DefaultValue("10485760") long maxBytes,
        @DefaultValue("4") int maxPerRequest
) {

    public static AttachmentProperties defaults() {
        return new AttachmentProperties("data/attachments", 10L * 1024 * 1024, 4);
    }
}