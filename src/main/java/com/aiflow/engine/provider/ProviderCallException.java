package com.aiflow.engine.provider;

/**
 * 单次 provider 调用失败（网络、超时、协议、鉴权）。
 *
 * <p>与 {@link CapabilityUnavailableException} 的区别：后者是路由阶段就找不到档位，
 * 前者是选了档位但调用没成 —— 后者可以换下一档再试，前者只能落到预设话术。
 */
public class ProviderCallException extends RuntimeException {

    private final String providerId;

    public ProviderCallException(String providerId, String message, Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
    }

    public ProviderCallException(String providerId, String message) {
        this(providerId, message, null);
    }

    public String getProviderId() {
        return providerId;
    }
}