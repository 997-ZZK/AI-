package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AudioProviderException;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** 只持有环境变量名与注入解析器；构造/能力探测不读取凭证，也不缓存变量值。 */
public final class AudioHttpCredentials implements Supplier<Map<String, String>> {

    private final String environmentName;
    private final Function<String, String> resolver;

    private AudioHttpCredentials(String environmentName, Function<String, String> resolver) {
        if (environmentName == null || !environmentName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("无效音频凭证环境变量名");
        }
        this.environmentName = environmentName;
        this.resolver = Objects.requireNonNull(resolver);
    }

    /** 解析器由装配方显式注入；测试只传合成值，本切片不提供系统环境默认解析器。 */
    public static AudioHttpCredentials bearer(String environmentName, Function<String, String> resolver) {
        return new AudioHttpCredentials(environmentName, resolver);
    }

    @Override
    public Map<String, String> get() {
        try {
            String value = resolver.apply(environmentName);
            if (value == null || value.isBlank() || value.length() > 4000
                    || value.chars().anyMatch(c -> c <= 32 || c >= 127)) {
                throw new IllegalArgumentException();
            }
            return Map.of("Authorization", "Bearer " + value);
        } catch (RuntimeException ignored) {
            throw new AudioProviderException(AudioProviderException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public String toString() {
        return "AudioHttpCredentials[environmentName=" + environmentName + "]";
    }
}
