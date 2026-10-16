package com.aiflow.engine.provider;

import java.util.List;
import java.util.Set;

/**
 * 候选档位全部调用失败。带上每一档的失败原因 —— 排查时要能直接看出「是云端超时还是本地模型没拉」，
 * 而不是一句笼统的「调用失败」。
 *
 * <p>{@code partialOutputDelivered} 标出「已经给用户吐过字了」。这种情况下上层不该再换档重来：
 * 两档的输出拼在一起会得到一段读不通的话，只能按失败处理（或重试整轮），让用户看到完整的另一版答案。
 */
public class AllProvidersFailedException extends RuntimeException {

    private final Set<String> requiredCapabilities;
    private final List<FailedAttempt> attempts;
    private final boolean partialOutputDelivered;

    public AllProvidersFailedException(Set<String> requiredCapabilities, List<FailedAttempt> attempts) {
        this(requiredCapabilities, attempts, false);
    }

    public AllProvidersFailedException(Set<String> requiredCapabilities, List<FailedAttempt> attempts,
                                       boolean partialOutputDelivered) {
        super(buildMessage(requiredCapabilities, attempts, partialOutputDelivered));
        this.requiredCapabilities = Set.copyOf(requiredCapabilities);
        this.attempts = List.copyOf(attempts);
        this.partialOutputDelivered = partialOutputDelivered;
    }

    public Set<String> getRequiredCapabilities() {
        return requiredCapabilities;
    }

    public List<FailedAttempt> getAttempts() {
        return attempts;
    }

    public boolean isPartialOutputDelivered() {
        return partialOutputDelivered;
    }

    private static String buildMessage(Set<String> capabilities, List<FailedAttempt> attempts,
                                       boolean partialOutputDelivered) {
        StringBuilder builder = new StringBuilder("所有候选 provider 都调用失败（能力：")
                .append(String.join("/", capabilities)).append("）：");
        for (FailedAttempt attempt : attempts) {
            builder.append("[").append(attempt.providerId()).append("] ").append(attempt.error()).append("; ");
        }
        if (partialOutputDelivered) {
            builder.append("（已有部分内容推送给用户，不再切换档位）");
        }
        return builder.toString().strip();
    }

    public record FailedAttempt(String providerId, String error) {
    }
}