package com.aiflow.engine.approval.notify;

import java.util.Map;

/**
 * 通知渠道 SPI。
 *
 * <p>渠道实现**不许**阻塞太久（有超时就设上），也不许把异常吞成静默失败：调度器会兜住实现抛出的异常、
 * 计入 failed 并保留 lastError。一个渠道挂掉不影响另一个，更不影响审批本身。
 */
public interface ApprovalNotifier {

    /** 渠道名（出现在日志与状态接口里）。 */
    String channel();

    /**
     * 装配期算一次的开关：false = 不投递，但**仍出现在状态接口**里（否则「配了但没生效」会查不出来）。
     */
    default boolean enabled() {
        return true;
    }

    /** 渠道自己的状态片段（不许放密钥或带凭证的 URL 路径）。 */
    default Map<String, Object> status() {
        return Map.of();
    }

    void notify(ApprovalNotification notification) throws Exception;
}
