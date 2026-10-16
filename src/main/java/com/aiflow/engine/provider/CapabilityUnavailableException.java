package com.aiflow.engine.provider;

import java.util.Set;

public class CapabilityUnavailableException extends RuntimeException {

    private final Set<String> requiredCapabilities;

    public CapabilityUnavailableException(Set<String> requiredCapabilities) {
        super("没有可用的 provider 满足能力要求: " + String.join(", ", requiredCapabilities));
        this.requiredCapabilities = Set.copyOf(requiredCapabilities);
    }

    public Set<String> getRequiredCapabilities() {
        return requiredCapabilities;
    }
}
