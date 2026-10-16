package com.aiflow.engine.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OAuth 令牌客户端：取令牌、复用、到期前刷新、并发单飞、失败信息可读、密钥不进日志。
 *
 * <p>夹具 {@link FakeOAuthServerMain} 每次下发不同的令牌，所以「刷新过没有」是可断言的，而不是靠猜。
 */
class McpOAuthTokenProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpOAuthTokenProvider provider(String serverId, String tokenUrl, String clientIdEnv,
                                                  String clientSecretEnv, Long earlyRefreshSeconds,
                                                  Function<String, String> env) {
        McpServerConfig.OAuth oauth = new McpServerConfig.OAuth(tokenUrl, clientIdEnv, clientSecretEnv, null,
                null, Map.of(), earlyRefreshSeconds);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return new McpOAuthTokenProvider(serverId, oauth, env, client, null, MAPPER);
    }

    private static McpOAuthTokenProvider provider(String tokenUrl, String clientIdEnv, String clientSecretEnv,
                                                  Long earlyRefreshSeconds, Function<String, String> env) {
        return provider("oauth-server", tokenUrl, clientIdEnv, clientSecretEnv, earlyRefreshSeconds, env);
    }

    private static Function<String, String> env(String clientId, String clientSecret) {
        return name -> switch (name) {
            case "OAUTH_CLIENT_ID" -> clientId;
            case "OAUTH_CLIENT_SECRET" -> clientSecret;
            default -> null;
        };
    }

    @Test
    void fetchesTokenWithClientCredentialsAndReusesIt() throws IOException {
        try (FakeOAuthServerMain server = FakeOAuthServerMain.startClientCredentials("cid", "secret", 3600)) {
            McpOAuthTokenProvider provider = provider(server.tokenUrl(), "OAUTH_CLIENT_ID", "OAUTH_CLIENT_SECRET",
                    60L, env("cid", "secret"));

            String first = provider.accessToken();
            String second = provider.accessToken();

            assertThat(first).isNotBlank().isEqualTo(server.currentToken());
            assertThat(second).isEqualTo(first);
            // 令牌还在有效期内就不该再打一次令牌端点：限流与配额都经不起「每次调用都换令牌」。
            assertThat(server.tokenRequestCount()).isEqualTo(1);
            assertThat(server.grants()).containsExactly("client_credentials");
        }
    }

    @Test
    void refreshesBeforeExpiry() throws IOException, InterruptedException {
        try (FakeOAuthServerMain server = FakeOAuthServerMain.startClientCredentials("cid", "secret", 2)) {
            McpOAuthTokenProvider provider = provider(server.tokenUrl(), "OAUTH_CLIENT_ID", "OAUTH_CLIENT_SECRET",
                    1L, env("cid", "secret"));

            String first = provider.accessToken();
            Thread.sleep(1_100);
            String second = provider.accessToken();

            assertThat(second).isNotEqualTo(first);
            assertThat(server.tokenRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void refreshesOnlyOnceWhenManyCallsRace() throws Exception {
        try (FakeOAuthServerMain server = FakeOAuthServerMain.startClientCredentials("cid", "secret", 60)) {
            server.setResponseDelayMillis(300);
            McpOAuthTokenProvider provider = provider(server.tokenUrl(), "OAUTH_CLIENT_ID", "OAUTH_CLIENT_SECRET",
                    0L, env("cid", "secret"));
            CountDownLatch start = new CountDownLatch(1);
            List<String> tokens = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Thread> threads = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        tokens.add(provider.accessToken());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                thread.start();
                threads.add(thread);
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join(5_000);
            }

            assertThat(tokens).hasSize(4).doesNotContainNull();
            assertThat(tokens.stream().distinct()).hasSize(1);
            // 四个并发只换一次令牌：令牌端点常有限流，「并发」不该被翻译成「被限流」。
            assertThat(server.tokenRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void reportsMissingClientIdEnvClearly() throws IOException {
        try (FakeOAuthServerMain server = FakeOAuthServerMain.startClientCredentials("cid", "secret", 3600)) {
            McpOAuthTokenProvider provider = provider(server.tokenUrl(), "OAUTH_CLIENT_ID", "OAUTH_CLIENT_SECRET",
                    60L, name -> null);

            assertThatThrownBy(provider::accessToken)
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("OAUTH_CLIENT_ID")
                    .hasMessageContaining("未设置");
            assertThat(server.tokenRequestCount()).isZero();
        }
    }

    @Test
    void reportsRejectedCredentialsWithoutLeakingTheSecret() throws IOException {
        try (FakeOAuthServerMain server = FakeOAuthServerMain.startClientCredentials("cid", "right-secret", 3600)) {
            McpOAuthTokenProvider provider = provider(server.tokenUrl(), "OAUTH_CLIENT_ID", "OAUTH_CLIENT_SECRET",
                    60L, env("cid", "wrong-secret"));

            // 提示里给的是「去检查哪两个环境变量」，而不是回显 secret —— 异常消息也会进日志。
            assertThatThrownBy(provider::accessToken)
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("401")
                    .hasMessageContaining("OAUTH_CLIENT_SECRET")
                    .hasMessageNotContaining("wrong-secret");
            assertThat(server.rejectedCount()).isEqualTo(1);
        }
    }

    @Test
    void usesRefreshTokenGrantWhenConfigured() throws IOException {
        try (FakeOAuthServerMain server = FakeOAuthServerMain.startRefreshToken("cid", "secret", "refresh-1", 3600)) {
            McpServerConfig.OAuth oauth = new McpServerConfig.OAuth(server.tokenUrl(), "OAUTH_CLIENT_ID",
                    "OAUTH_CLIENT_SECRET", "OAUTH_REFRESH_TOKEN", "mcp.read", Map.of(), 60L);
            Function<String, String> env = name -> switch (name) {
                case "OAUTH_CLIENT_ID" -> "cid";
                case "OAUTH_CLIENT_SECRET" -> "secret";
                case "OAUTH_REFRESH_TOKEN" -> "refresh-1";
                default -> null;
            };
            McpOAuthTokenProvider provider = new McpOAuthTokenProvider("refresh-server", oauth, env,
                    HttpClient.newHttpClient(), null, MAPPER);

            assertThat(provider.accessToken()).isEqualTo(server.currentToken());
            assertThat(server.grants()).containsExactly("refresh_token");
        }
    }

    @Test
    void describesValidityWithoutExposingTheToken() throws IOException {
        try (FakeOAuthServerMain server = FakeOAuthServerMain.startClientCredentials("cid", "secret", 3600)) {
            McpOAuthTokenProvider provider = provider(server.tokenUrl(), "OAUTH_CLIENT_ID", "OAUTH_CLIENT_SECRET",
                    60L, env("cid", "secret"));

            assertThat(provider.describe()).isEqualTo("oauth(client_credentials, 未获取)");
            String token = provider.accessToken();

            assertThat(provider.describe()).contains("oauth(client_credentials").doesNotContain(token);
        }
    }

    @Test
    void reportsUnreachableTokenEndpointClearly() throws IOException {
        // 不依赖假服务端：直接指向一个必然连不上的端口（1 号端口上不会有人监听）。
        try (FakeOAuthServerMain ignored = FakeOAuthServerMain.startClientCredentials("cid", "secret", 3600)) {
            McpOAuthTokenProvider provider = provider("http://127.0.0.1:1/token",
                    "OAUTH_CLIENT_ID", "OAUTH_CLIENT_SECRET", 60L, env("cid", "secret"));

            assertThatThrownBy(provider::accessToken)
                    .isInstanceOf(McpCallException.class)
                    .hasMessageContaining("令牌端点");
        }
    }
}
