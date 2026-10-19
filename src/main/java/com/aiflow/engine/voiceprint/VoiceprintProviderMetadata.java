package com.aiflow.engine.voiceprint;

import java.util.Set;

/**
 * 声纹 provider 的非敏感诊断元数据。
 *
 * <p>能力集合和模型版本可以出现在能力报告或诊断日志中；模板引用和声纹向量
 * 不属于此类型。</p>
 */
public record VoiceprintProviderMetadata(String providerId, String modelVersion, Set<String> capabilities) {

    public static final String VERIFY_ONE_TO_ONE = "verify-1-to-1";
    public static final String ENROLL = "enroll";
    public static final String DELETE = "delete";

    public VoiceprintProviderMetadata {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("voiceprint provider id 不能为空");
        }
        providerId = providerId.strip();
        modelVersion = modelVersion == null || modelVersion.isBlank() ? null : modelVersion.strip();
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public static VoiceprintProviderMetadata defaultFor(String providerId) {
        return new VoiceprintProviderMetadata(providerId, null,
                Set.of(VERIFY_ONE_TO_ONE, ENROLL, DELETE));
    }

    @Override
    public String toString() {
        return "VoiceprintProviderMetadata[providerId=" + providerId
                + ", modelVersion=" + modelVersion + ", capabilities=" + capabilities + "]";
    }
}
