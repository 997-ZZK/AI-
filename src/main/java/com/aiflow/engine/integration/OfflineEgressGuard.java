package com.aiflow.engine.integration;

import com.aiflow.engine.config.OfflineProperties;

/**
 * 统一的 M5-A HTTP 出网门禁。
 *
 * <p>HTTP provider 在构造阶段调用它，因此被门禁拒绝的端点不会注册探活、不会注册 provider，
 * 也不会因为后续路由顺序变化而偶然发出请求。返回值只表达允许/拒绝；拒绝原因使用固定文本。
 */
public final class OfflineEgressGuard {

    private final OfflineProperties properties;

    public OfflineEgressGuard(OfflineProperties properties) {
        this.properties = properties == null ? OfflineProperties.disabled() : properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public boolean allows(String id, String tier, String baseUrl) {
        if (!properties.enabled()) {
            return true;
        }
        // 离线 profile 只允许 local 档。即使 cloud id 指向 localhost，也不能把它当本地模型。
        if (tier != null && !tier.isBlank() && !"local".equalsIgnoreCase(tier)
                && !"mock".equalsIgnoreCase(tier)) {
            return false;
        }
        return properties.allows(baseUrl);
    }

    public String rejectionReason() {
        return properties.rejectionReason();
    }
}
