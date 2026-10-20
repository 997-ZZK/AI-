package com.aiflow.engine.tool;

import com.aiflow.engine.config.ToolProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfGuardTest {

    private static final String PUBLIC_IP = "93.184.216.34";

    @Test
    void allowsWhitelistedHostResolvingToPublicAddress() {
        SsrfGuard guard = guard(List.of("api.example.com"), List.of(), List.of(80, 443), publicResolver());

        assertThatCode(() -> guard.validate("https://api.example.com/v1/items")).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("http://api.example.com:80/x")).doesNotThrowAnyException();
    }

    @Test
    void rejectsHostOutsideAllowlist() {
        SsrfGuard guard = guard(List.of("api.example.com"), List.of(), List.of(80, 443), publicResolver());

        assertThatThrownBy(() -> guard.validate("https://evil.example.org/x"))
                .isInstanceOf(ToolBlockedException.class)
                .hasMessageContaining("allow-hosts");
    }

    @Test
    void rejectsNonHttpSchemeAndUserInfo() {
        SsrfGuard guard = guard(List.of("api.example.com"), List.of(), List.of(80, 443), publicResolver());

        assertThatThrownBy(() -> guard.validate("file:///C:/Windows/win.ini"))
                .hasMessageContaining("只支持 http/https");
        assertThatThrownBy(() -> guard.validate("https://user:pw@api.example.com/x"))
                .as("userinfo 是凭证泄露面：URL 会进日志与快照")
                .hasMessageContaining("userinfo");
    }

    @Test
    void rejectsPortOutsideAllowlist() {
        SsrfGuard guard = guard(List.of("api.example.com"), List.of(), List.of(80, 443), publicResolver());

        assertThatThrownBy(() -> guard.validate("https://api.example.com:8080/x"))
                .hasMessageContaining("allow-ports");
    }

    @Test
    void rejectsPrivateAndMetadataAddressesEvenWhenHostIsWhitelisted() {
        SsrfGuard guard = guard(List.of("internal.example.com"), List.of(), List.of(80, 443),
                host -> List.of(address("10.1.2.3")));

        assertThatThrownBy(() -> guard.validate("https://internal.example.com/x"))
                .as("域名可以指向内网地址：白名单只校验字符串是不够的")
                .hasMessageContaining("内网/受限地址");

        SsrfGuard metadata = guard(List.of("metadata.example.com"), List.of(), List.of(80, 443),
                host -> List.of(address("169.254.169.254")));
        assertThatThrownBy(() -> metadata.validate("http://metadata.example.com/latest/meta-data/"))
                .as("169.254.169.254 是云元数据服务，链路本地段必须整体拒绝")
                .hasMessageContaining("内网/受限地址");
    }

    @Test
    void allowsPrivateAddressOnlyForExplicitlyAllowedHost() {
        SsrfGuard guard = guard(List.of("localhost"), List.of("localhost"), List.of(8080),
                host -> List.of(address("127.0.0.1")));

        assertThatCode(() -> guard.validate("http://localhost:8080/api/v1/capabilities"))
                .doesNotThrowAnyException();

        SsrfGuard strict = guard(List.of("localhost"), List.of(), List.of(8080),
                host -> List.of(address("127.0.0.1")));
        assertThatThrownBy(() -> strict.validate("http://localhost:8080/api/v1/capabilities"))
                .hasMessageContaining("内网/受限地址");
    }

    @Test
    void rejectsUnresolvableHost() {
        SsrfGuard guard = guard(List.of("api.example.com"), List.of(), List.of(80, 443), host -> List.of());

        assertThatThrownBy(() -> guard.validate("https://api.example.com/x"))
                .hasMessageContaining("无法解析");
    }

    @Test
    void malformedUrlDiagnosticsDoNotEchoQueryCredentials() {
        SsrfGuard guard = guard(List.of("api.example.com"), List.of(), List.of(80, 443), publicResolver());
        String marker = "private-ssrf-query-marker";

        assertThatThrownBy(() -> guard.validate("https://api.example.com/%zz?token=" + marker))
                .hasMessage("工具 url 无法解析")
                .hasMessageNotContaining(marker);
        assertThatThrownBy(() -> guard.validate("https:///missing-host?token=" + marker))
                .hasMessage("工具 url 缺少 host")
                .hasMessageNotContaining(marker);
    }

    @Test
    void wildcardMatchesSubdomainButNotApexOrSuffixTrick() {
        SsrfGuard guard = guard(List.of("*.example.com"), List.of(), List.of(80, 443), publicResolver());

        assertThatCode(() -> guard.validate("https://a.example.com/x")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.validate("https://example.com/x")).hasMessageContaining("allow-hosts");
        assertThatThrownBy(() -> guard.validate("https://evilexample.com/x"))
                .as("通配必须按域名边界匹配：evilexample.com 不是 example.com 的子域")
                .hasMessageContaining("allow-hosts");
    }

    private SsrfGuard guard(List<String> allowHosts, List<String> allowPrivate, List<Integer> ports,
                            Function<String, List<InetAddress>> resolver) {
        ToolProperties properties = new ToolProperties(true, 3_000, 65_536, 8, 3, allowHosts, allowPrivate,
                ports, Map.of());
        return new SsrfGuard(properties, resolver);
    }

    private static Function<String, List<InetAddress>> publicResolver() {
        return host -> List.of(address(PUBLIC_IP));
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
