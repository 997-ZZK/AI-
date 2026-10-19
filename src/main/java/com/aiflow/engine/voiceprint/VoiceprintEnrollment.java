package com.aiflow.engine.voiceprint;

/** 声纹模板录入结果；模板引用仅供内部 provider 组合使用，不是普通 API 字段。 */
public record VoiceprintEnrollment(Status status, Reason reason, String templateReference) {

    public enum Status {
        ENROLLED,
        UNAVAILABLE,
        INVALID_SAMPLE
    }

    public enum Reason {
        ENROLLED("声纹模板已录入"),
        PROVIDER_UNAVAILABLE("声纹能力不可用"),
        DISABLED("声纹能力未配置"),
        INVALID_SAMPLE("声纹样本无效");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    public VoiceprintEnrollment {
        if (status == null || reason == null) {
            throw new IllegalArgumentException("voiceprint enrollment status and reason are required");
        }
        if ((status == Status.ENROLLED) != (templateReference != null && !templateReference.isBlank())) {
            throw new IllegalArgumentException("an enrolled result requires an opaque template reference");
        }
        if (templateReference != null) {
            templateReference = templateReference.strip();
        }
        boolean reasonMatches = switch (status) {
            case ENROLLED -> reason == Reason.ENROLLED;
            case UNAVAILABLE -> reason == Reason.PROVIDER_UNAVAILABLE || reason == Reason.DISABLED;
            case INVALID_SAMPLE -> reason == Reason.INVALID_SAMPLE;
        };
        if (!reasonMatches) {
            throw new IllegalArgumentException("voiceprint enrollment reason does not match status");
        }
    }

    public static VoiceprintEnrollment enrolled(String templateReference) {
        return new VoiceprintEnrollment(Status.ENROLLED, Reason.ENROLLED, templateReference);
    }

    public static VoiceprintEnrollment unavailable(Reason reason) {
        if (reason != Reason.PROVIDER_UNAVAILABLE && reason != Reason.DISABLED) {
            throw new IllegalArgumentException("unavailable result requires an unavailable reason");
        }
        return new VoiceprintEnrollment(Status.UNAVAILABLE, reason, null);
    }

    public static VoiceprintEnrollment invalidSample() {
        return new VoiceprintEnrollment(Status.INVALID_SAMPLE, Reason.INVALID_SAMPLE, null);
    }

    @Override
    public String toString() {
        return "VoiceprintEnrollment[status=" + status + ", reason=" + reason
                + ", templateReference=<redacted>]";
    }
}
