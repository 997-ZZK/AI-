package com.aiflow.engine.tool;

import com.aiflow.engine.config.ToolProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 出网护栏：工具调用的 URL 必须同时过四道检查才允许发出。
 *
 * <ol>
 *   <li>协议只接受 http / https；带 userinfo（{@code https://user:pass@host}）的一律拒绝 —— 那是凭证泄露面</li>
 *   <li>端口必须在 allow-ports 内（默认 80/443），避免被当成端口扫描器</li>
 *   <li>host 必须在 allow-hosts 内（支持 {@code *.example.com} 通配），否则连配置漏写都挡得住</li>
 *   <li>解析出的地址不能是回环 / 内网 / 链路本地 / 保留地址（169.254.169.254 云元数据就在链路本地段），
 *       除非该 host 显式列在 allow-private-hosts 里</li>
 * </ol>
 *
 * <p>第 4 条要放在解析之后：域名可以指向内网地址（DNS rebinding 的简化版），只看字符串是挡不住的。
 * 解析器做成可注入的，测试才能在不依赖真实 DNS 的前提下覆盖这些分支。
 */
@Component
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    private final ToolProperties properties;
    private final Function<String, List<InetAddress>> resolver;

    @Autowired
    public SsrfGuard(ToolProperties properties) {
        this(properties, SsrfGuard::resolveAll);
    }

    SsrfGuard(ToolProperties properties, Function<String, List<InetAddress>> resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    /** 校验并返回规范化后的 URI；不通过时抛 {@link ToolBlockedException}（消息可直接给用户看）。 */
    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ToolBlockedException("工具 url 为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new ToolBlockedException("工具 url 无法解析: " + rawUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ToolBlockedException("工具只支持 http/https，拒绝协议: " + (scheme.isEmpty() ? "(空)" : scheme));
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new ToolBlockedException("工具 url 不允许携带 userinfo（凭证请放 headers，且只从环境变量注入）");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ToolBlockedException("工具 url 缺少 host: " + rawUrl);
        }
        int port = uri.getPort() < 0 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        if (!properties.allowPorts().contains(port)) {
            throw new ToolBlockedException("工具端口不在 allow-ports 内: " + port + "（当前白名单 " + properties.allowPorts() + "）");
        }
        if (!hostAllowed(host)) {
            throw new ToolBlockedException("工具 host 不在 allow-hosts 内: " + host);
        }
        if (!privateHostAllowed(host)) {
            List<InetAddress> addresses;
            try {
                addresses = resolver.apply(host);
            } catch (RuntimeException e) {
                throw new ToolBlockedException("工具 host 无法解析: " + host);
            }
            if (addresses == null || addresses.isEmpty()) {
                throw new ToolBlockedException("工具 host 无法解析: " + host);
            }
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    log.warn("工具调用被拒绝：host={} 解析到受限地址 {}", host, address.getHostAddress());
                    throw new ToolBlockedException("工具 host " + host + " 解析到内网/受限地址 "
                            + address.getHostAddress() + "，已拒绝（需要放行请加入 aiflow.tools.allow-private-hosts）");
                }
            }
        }
        return uri;
    }

    public boolean hostAllowed(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        for (String pattern : properties.allowHosts()) {
            if (matchesHost(pattern, normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean privateHostAllowed(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        for (String pattern : properties.allowPrivateHosts()) {
            if (matchesHost(pattern, normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 精确匹配，或通配子域（{@code *.example.com} 匹配 a.example.com，不匹配 example.com 与 evilexample.com）。 */
    static boolean matchesHost(String pattern, String host) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String normalized = pattern.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("*.")) {
            String suffix = normalized.substring(1);
            return host.endsWith(suffix) && host.length() > suffix.length();
        }
        return normalized.equals(host);
    }

    /** 回环 / 任意 / 组播 / 链路本地 / 站点本地 / IPv6 ULA：都不是工具该去的地方。 */
    static boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address)
                || isCgnat(address);
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** 100.64.0.0/10（运营商级 NAT）：既不是公网也不是内网，但同样不该被工具访问。 */
    private static boolean isCgnat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    private static List<InetAddress> resolveAll(String host) {
        try {
            return Arrays.asList(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            return List.of();
        }
    }
}