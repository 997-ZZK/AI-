package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.util.Locale;
import java.util.List;

/**
 * M5-A 的离线出网策略。
 *
 * <p>这个开关是出网门禁，不是 provider 路由提示：开启后只有显式允许的本机地址能被
 * HTTP provider 装配。配置里的远端 endpoint 即使被环境变量重新打开，也会被跳过。
 * 凭证仍只保存环境变量名/由 provider 在调用时读取，门禁本身不会读取凭证。
 */
@ConfigurationProperties(prefix = "aiflow.offline")
public record OfflineProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean loopbackOnly,
        @DefaultValue({"localhost", "127.0.0.1", "[::1]", "::1"}) List<String> allowedHosts
) {

    public OfflineProperties {
        allowedHosts = allowedHosts == null ? List.of("localhost", "127.0.0.1", "[::1]", "::1")
                : allowedHosts.stream().map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }

    public static OfflineProperties disabled() {
        return new OfflineProperties(false, true, List.of("localhost", "127.0.0.1", "[::1]", "::1"));
    }

    /** 返回固定原因，避免把 URL 或上游诊断带进能力报告/日志。 */
    public String rejectionReason() {
        return "离线模式只允许 loopback provider endpoint";
    }

    /**
     * 检查端点地址是否处于离线允许范围。离线模式下不允许非 HTTP(S) scheme，
     * 也不把 URI 的用户信息、query 或 fragment 当作可配置的放行依据。
     */
    public boolean allows(String baseUrl) {
        if (!enabled) {
            return true;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl.strip());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                return false;
            }
            // An enabled offline profile is an isolation boundary.  Do not allow a
            // configuration flag to turn it into a remote egress profile.
            // loopbackOnly remains a bound property for backwards-compatible config
            // binding, but is intentionally ignored while offline mode is enabled.
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return isLoopback(normalizedHost)
                    && (allowedHosts.isEmpty() || allowedHosts.contains(normalizedHost));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }
}
