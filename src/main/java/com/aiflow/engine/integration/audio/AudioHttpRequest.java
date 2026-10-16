package com.aiflow.engine.integration.audio;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 可信协议适配器创建的请求；不对用户开放 URL，且不在诊断字符串中暴露任何报文。 */
public record AudioHttpRequest(URI endpoint, String contentType, String accept,
                               Map<String, String> headers, byte[] body, boolean retrySafe) {

    private static final Set<String> RESTRICTED_HEADERS = Set.of("host", "content-length", "content-type", "accept",
            "connection", "expect", "upgrade", "transfer-encoding", "trailer", "te");

    public AudioHttpRequest {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null || endpoint.getRawQuery() != null
                || !allowedEndpoint(endpoint) || body == null) {
            throw new IllegalArgumentException("无效音频 HTTP 请求配置");
        }
        requireHeaderValue(contentType, 128);
        requireHeaderValue(accept, 128);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (headers.size() > 32) {
            throw new IllegalArgumentException("无效音频 HTTP 请求配置");
        }
        Set<String> seenHeaders = new HashSet<>();
        for (var header : headers.entrySet()) {
            String name = header.getKey();
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!name.matches("[A-Za-z0-9-]{1,64}") || RESTRICTED_HEADERS.contains(normalized)
                    || normalized.startsWith("proxy-") || !seenHeaders.add(normalized)) {
                throw new IllegalArgumentException("无效音频 HTTP 请求配置");
            }
            requireHeaderValue(header.getValue(), 4096);
        }
        body = Arrays.copyOf(body, body.length);
    }

    @Override
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    @Override
    public String toString() {
        return "AudioHttpRequest[bodyBytes=" + body.length + ", headerCount=" + headers.size()
                + ", retrySafe=" + retrySafe + "]";
    }

    private static boolean allowedEndpoint(URI endpoint) {
        if ("https".equalsIgnoreCase(endpoint.getScheme())) {
            return true;
        }
        String host = endpoint.getHost();
        return "http".equalsIgnoreCase(endpoint.getScheme())
                && ("127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host));
    }

    private static void requireHeaderValue(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(c -> c < 32 || c >= 127)) {
            throw new IllegalArgumentException("无效音频 HTTP 请求配置");
        }
    }
}
