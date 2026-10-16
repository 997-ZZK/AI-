package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import com.aiflow.engine.integration.audio.JdkAudioHttpTransport;

import java.net.URI;
import java.time.Duration;

/**
 * 本地 ASR/TTS sidecar 的传输边界配置。
 *
 * <p>这里只描述 loopback 地址、路径和有界 HTTP 传输参数，不描述任何厂商的 JSON
 * 字段。请求/响应编码由 {@code LocalAsrProtocol}/{@code LocalTtsProtocol} bean 提供；
 * 没有协议 bean 或 endpoint 配置时，AudioConfig 不会把通道标记为可用。</p>
 */
@ConfigurationProperties(prefix = "aiflow.audio.local")
public record AudioSidecarProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String asrPath,
        @DefaultValue("") String ttsPath,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration callTimeout,
        @DefaultValue("5242880") int maxRequestBytes,
        @DefaultValue("5242880") int maxResponseBytes,
        @DefaultValue("1") int maxAttempts,
        @DefaultValue("0s") Duration retryDelay,
        @DefaultValue("8") int maxConcurrentCalls
) {

    public AudioSidecarProperties {
        baseUrl = normalize(baseUrl);
        asrPath = normalize(asrPath);
        ttsPath = normalize(ttsPath);
        if (!validTimeout(connectTimeout) || !validTimeout(callTimeout)
                || maxRequestBytes <= 0 || maxResponseBytes <= 0
                || maxAttempts < 1 || maxAttempts > 3 || retryDelay == null || retryDelay.isNegative()
                || retryDelay.compareTo(Duration.ofSeconds(1)) > 0
                || maxConcurrentCalls < 1 || maxConcurrentCalls > 64) {
            throw new IllegalArgumentException("无效本地音频 sidecar 传输限制");
        }
    }

    public static AudioSidecarProperties defaults() {
        return new AudioSidecarProperties(false, "", "", "", Duration.ofSeconds(2), Duration.ofSeconds(10),
                5 * 1024 * 1024, 5 * 1024 * 1024, 1, Duration.ZERO, 8);
    }

    /** 仅接受 http(s) loopback 地址；解析失败按未配置处理。 */
    public URI endpoint(String path) {
        if (baseUrl.isBlank() || path == null || path.isBlank()) {
            return null;
        }
        try {
            URI base = URI.create(baseUrl);
            if (base.getUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                    || !loopback(base) || !("http".equalsIgnoreCase(base.getScheme())
                    || "https".equalsIgnoreCase(base.getScheme()))) {
                return null;
            }
            URI suffix = URI.create(path);
            if (suffix.isAbsolute() || suffix.getUserInfo() != null || suffix.getRawQuery() != null
                    || suffix.getRawFragment() != null || suffix.getPath() == null
                    || suffix.getPath().isBlank()) {
                return null;
            }
            String relative = suffix.getPath().startsWith("/") ? suffix.getPath().substring(1) : suffix.getPath();
            // URI.resolve treats a base path without a trailing slash as a file
            // and drops its last segment (e.g. /v1 + asr -> /asr).  Sidecars
            // commonly expose a versioned base path, so resolve against an
            // explicit directory form while retaining the loopback checks.
            String baseWithDirectoryPath = base.toString().endsWith("/") ? base.toString() : base + "/";
            URI endpoint = URI.create(baseWithDirectoryPath).resolve(relative);
            return loopback(endpoint) && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null
                    ? endpoint : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public JdkAudioHttpTransport.Options transportOptions() {
        return new JdkAudioHttpTransport.Options(connectTimeout, callTimeout, maxRequestBytes, maxResponseBytes,
                maxAttempts, retryDelay, maxConcurrentCalls);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean validTimeout(Duration value) {
        return value != null && !value.isZero() && !value.isNegative()
                && value.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    private static boolean loopback(URI uri) {
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }

}
