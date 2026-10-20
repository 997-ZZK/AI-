package com.aiflow.engine.tool;

import java.net.URI;
import java.util.Locale;

/**
 * 供 API、运行快照与日志使用的 URL 安全摘要。
 *
 * <p>工具 URL 的 query 会拼入运行时入参，可能包含凭证或其他私密值；诊断信息
 * 只能保留协议、主机、显式端口和路径。实际请求仍使用原始 URI。
 */
public final class ToolUrlSummary {

    private ToolUrlSummary() {
    }

    /**
     * 去掉 userinfo、query 与 fragment。URI 无法安全解析为 HTTP 端点时返回固定占位符，
     * 避免异常路径把原始字符串带回响应或运行快照。
     */
    public static String describe(URI uri) {
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || scheme.isBlank() || host == null || host.isBlank()) {
            return "(unavailable URL)";
        }
        StringBuilder safe = new StringBuilder(scheme.toLowerCase(Locale.ROOT))
                .append("://").append(host);
        if (uri.getPort() >= 0) {
            safe.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        if (path != null && !path.isBlank()) {
            if (!path.startsWith("/")) {
                safe.append('/');
            }
            safe.append(path);
        }
        return safe.toString();
    }
}
