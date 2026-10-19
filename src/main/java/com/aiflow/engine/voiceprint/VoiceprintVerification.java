package com.aiflow.engine.voiceprint;

/** 1:1 声纹确认结果，结果正文不携带样本、模板或算法原始报文。 */
public record VoiceprintVerification(Outcome outcome, Reason reason,
                                     VoiceprintConfidenceRange confidence) {

    public enum Outcome {
        VERIFIED,
        REJECTED,
        UNAVAILABLE,
        INVALID_SAMPLE
    }

    /** 对外稳定的固定原因；调用方不得把 provider 原始错误正文带入结果。 */
    public enum Reason {
        MATCH("声纹确认通过"),
        NO_MATCH("声纹确认未通过"),
        SUBJECT_NOT_ENROLLED("主体未录入声纹模板"),
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

    public VoiceprintVerification {
        if (outcome == null || reason == null) {
            throw new IllegalArgumentException("voiceprint verification outcome and reason are required");
        }
        boolean scoreExpected = outcome == Outcome.VERIFIED
                || (outcome == Outcome.REJECTED && reason == Reason.NO_MATCH);
        if (scoreExpected != (confidence != null)) {
            throw new IllegalArgumentException("voiceprint confidence is required only for a completed verification");
        }
        boolean reasonMatches = switch (outcome) {
            case VERIFIED -> reason == Reason.MATCH;
            case REJECTED -> reason == Reason.NO_MATCH || reason == Reason.SUBJECT_NOT_ENROLLED;
            case UNAVAILABLE -> reason == Reason.PROVIDER_UNAVAILABLE || reason == Reason.DISABLED;
            case INVALID_SAMPLE -> reason == Reason.INVALID_SAMPLE;
        };
        if (!reasonMatches) {
            throw new IllegalArgumentException("voiceprint verification reason does not match outcome");
        }
    }

    public static VoiceprintVerification verified(VoiceprintConfidenceRange confidence) {
        return new VoiceprintVerification(Outcome.VERIFIED, Reason.MATCH, confidence);
    }

    public static VoiceprintVerification rejected(Reason reason, VoiceprintConfidenceRange confidence) {
        if (reason != Reason.NO_MATCH && reason != Reason.SUBJECT_NOT_ENROLLED) {
            throw new IllegalArgumentException("rejected result requires a rejection reason");
        }
        if (reason == Reason.NO_MATCH && confidence == null) {
            throw new IllegalArgumentException("no-match result requires confidence");
        }
        if (reason == Reason.SUBJECT_NOT_ENROLLED && confidence != null) {
            throw new IllegalArgumentException("subject-not-enrolled result must not contain confidence");
        }
        return new VoiceprintVerification(Outcome.REJECTED, reason, confidence);
    }

    public static VoiceprintVerification unavailable(Reason reason) {
        if (reason != Reason.PROVIDER_UNAVAILABLE && reason != Reason.DISABLED) {
            throw new IllegalArgumentException("unavailable result requires an unavailable reason");
        }
        return new VoiceprintVerification(Outcome.UNAVAILABLE, reason, null);
    }

    public static VoiceprintVerification invalidSample() {
        return new VoiceprintVerification(Outcome.INVALID_SAMPLE, Reason.INVALID_SAMPLE, null);
    }

    @Override
    public String toString() {
        return "VoiceprintVerification[outcome=" + outcome + ", reason=" + reason
                + ", confidence=" + confidence + "]";
    }
}
