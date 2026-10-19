package com.aiflow.engine.voiceprint;

/** 未配置声纹 provider 时的固定禁用实现；不访问网络、存储或算法。 */
public final class DisabledVoiceprintProvider implements VoiceprintProvider {

    static final DisabledVoiceprintProvider INSTANCE = new DisabledVoiceprintProvider();

    private DisabledVoiceprintProvider() {
    }

    @Override
    public String id() {
        return "disabled";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public VoiceprintProviderMetadata metadata() {
        return new VoiceprintProviderMetadata(id(), null, java.util.Set.of());
    }

    @Override
    public VoiceprintEnrollment enroll(String subjectId, VoiceprintSample sample) {
        VoiceprintProvider.requireSubjectId(subjectId);
        return VoiceprintEnrollment.unavailable(VoiceprintEnrollment.Reason.DISABLED);
    }

    @Override
    public VoiceprintVerification verify(String subjectId, VoiceprintSample sample) {
        VoiceprintProvider.requireSubjectId(subjectId);
        return VoiceprintVerification.unavailable(VoiceprintVerification.Reason.DISABLED);
    }

    @Override
    public VoiceprintDeletion delete(String subjectId) {
        VoiceprintProvider.requireSubjectId(subjectId);
        return VoiceprintDeletion.unavailable(VoiceprintDeletion.Reason.DISABLED);
    }
}
