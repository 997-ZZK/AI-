package com.aiflow.engine.voiceprint;

/** 可验证的模板删除结果。 */
public record VoiceprintDeletion(Status status, Reason reason) {

    public enum Status {
        DELETED,
        NOT_FOUND,
        UNAVAILABLE
    }

    public enum Reason {
        DELETED("声纹模板已删除"),
        NOT_FOUND("未找到声纹模板"),
        PROVIDER_UNAVAILABLE("声纹能力不可用"),
        DISABLED("声纹能力未配置");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    public VoiceprintDeletion {
        if (status == null || reason == null) {
            throw new IllegalArgumentException("voiceprint deletion status and reason are required");
        }
        boolean reasonMatches = switch (status) {
            case DELETED -> reason == Reason.DELETED;
            case NOT_FOUND -> reason == Reason.NOT_FOUND;
            case UNAVAILABLE -> reason == Reason.PROVIDER_UNAVAILABLE || reason == Reason.DISABLED;
        };
        if (!reasonMatches) {
            throw new IllegalArgumentException("voiceprint deletion reason does not match status");
        }
    }

    public static VoiceprintDeletion deleted() {
        return new VoiceprintDeletion(Status.DELETED, Reason.DELETED);
    }

    public static VoiceprintDeletion notFound() {
        return new VoiceprintDeletion(Status.NOT_FOUND, Reason.NOT_FOUND);
    }

    public static VoiceprintDeletion unavailable(Reason reason) {
        if (reason != Reason.PROVIDER_UNAVAILABLE && reason != Reason.DISABLED) {
            throw new IllegalArgumentException("unavailable result requires an unavailable reason");
        }
        return new VoiceprintDeletion(Status.UNAVAILABLE, reason);
    }

    @Override
    public String toString() {
        return "VoiceprintDeletion[status=" + status + ", reason=" + reason + "]";
    }
}
