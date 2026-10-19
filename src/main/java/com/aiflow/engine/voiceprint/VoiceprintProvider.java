package com.aiflow.engine.voiceprint;

/**
 * 厂商无关的声纹 1:1 确认端口。
 *
 * <p>这个端口只接受调用方已经确定的 {@code subjectId}，因此不做开放集识别，
 * 也不会从样本反推出主体。实现可以把模板放在自己的安全存储中，但不能把声纹
 * 向量放进返回值、日志、SSE 或运行快照。</p>
 *
 * <p>真实算法、sidecar 协议、阈值和模板存储属于后续切片；没有配置实现时使用
 * {@link #disabled()}，由调用方明确得到 {@code UNAVAILABLE/DISABLED}，不能静默
 * 伪造成验证失败或验证成功。</p>
 */
public interface VoiceprintProvider {

    /** 稳定的 provider 标识，只用于能力诊断。 */
    String id();

    /** 当前是否可以接受调用。未配置 provider 必须返回 {@code false}。 */
    default boolean available() {
        return true;
    }

    /** 只返回可写入诊断元数据的能力和模型版本，不包含样本或模板内容。 */
    default VoiceprintProviderMetadata metadata() {
        return VoiceprintProviderMetadata.defaultFor(id());
    }

    /** 录入或更新一个主体的模板引用。 */
    VoiceprintEnrollment enroll(String subjectId, VoiceprintSample sample);

    /** 针对已经指定的主体做 1:1 确认。 */
    VoiceprintVerification verify(String subjectId, VoiceprintSample sample);

    /** 删除主体模板；返回值必须能区分已删除、未找到和 provider 不可用。 */
    VoiceprintDeletion delete(String subjectId);

    /**
     * 校验并规范化主体标识，供实现复用。主体标识只允许作为 1:1 目标，不能从样本推断。
     */
    static String requireSubjectId(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("voiceprint subject id 不能为空");
        }
        return subjectId.strip();
    }

    /** 未配置时的安全默认实现；该对象不注册为 Spring provider bean。 */
    static VoiceprintProvider disabled() {
        return DisabledVoiceprintProvider.INSTANCE;
    }
}
