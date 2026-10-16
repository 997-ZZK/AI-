package com.aiflow.engine.approval.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 审批通知配置（`aiflow.approval.notify.*`）。
 *
 * @param enabled   总开关：关掉 = 一条都不投（队列都不入），待办接口与 SSE 事件照旧
 * @param queueSize 投递队列长度：满了丢**最老的**（近期状态更值得留），丢了多少会计数
 * @param dedupeSize 进程内去重窗口（(approvalId, event, level) -> 已投过），只用于挡住重复投递
 * @param webhook   webhook 渠道配置（出网要过与工具同一份白名单，见 {@link WebhookApprovalNotifier}）
 */
@ConfigurationProperties(prefix = "aiflow.approval.notify")
public record ApprovalNotifyProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("512") int queueSize,
        @DefaultValue("2000") int dedupeSize,
        @DefaultValue Webhook webhook) {

    /**
     * @param url      接收地址；空 = 未配置（即使 enabled=true 也不投，状态接口里标出来）
     * @param secret   HMAC-SHA256 签名密钥（只从环境变量注入，不进库、不进日志）；空 = 不带签名字段
     */
    public record Webhook(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String url,
            @DefaultValue("3000") long timeoutMs,
            @DefaultValue("") String secret) {
    }

    public static ApprovalNotifyProperties defaults() {
        return new ApprovalNotifyProperties(true, 512, 2000, new Webhook(false, "", 3000, ""));
    }
}
