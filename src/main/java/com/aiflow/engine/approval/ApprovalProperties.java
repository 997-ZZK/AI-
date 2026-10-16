package com.aiflow.engine.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 审批节点的部署级默认值（节点参数可覆盖）。
 *
 * @param sweepMs           超时扫描间隔
 * @param sweepEnabled      关掉扫描 = 审批永远不会自己过期（本地调试用；生产别关）
 * @param sweepBatchSize    一轮最多处理多少条到期审批
 * @param defaultWaitSeconds 节点没写 waitSeconds 时的决策窗口
 * @param maxWaitSeconds    决策窗口上限（挡住「等 3 年」这种配置错误）
 * @param defaultMaxLevel   节点没写 maxLevel 时的升级上限
 * @param adminUsers        谁都能批的管理员名单（鉴权落地前它只是**能力约束**，不是安全边界）
 */
@ConfigurationProperties(prefix = "aiflow.approval")
public record ApprovalProperties(
        @DefaultValue("5000") long sweepMs,
        @DefaultValue("true") boolean sweepEnabled,
        @DefaultValue("50") int sweepBatchSize,
        @DefaultValue("3600") int defaultWaitSeconds,
        @DefaultValue("2592000") int maxWaitSeconds,
        @DefaultValue("3") int defaultMaxLevel,
        @DefaultValue({}) String[] adminUsers) {

    public static ApprovalProperties defaults() {
        return new ApprovalProperties(5000, true, 50, 3600, 2_592_000, 3, new String[0]);
    }
}
