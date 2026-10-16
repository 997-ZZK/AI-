package com.aiflow.engine.approval.notify;

import com.aiflow.engine.config.ToolProperties;
import com.aiflow.engine.tool.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * webhook 渠道：载荷形状、签名、非 2xx、以及「配置不对时宁可不出网」。
 *
 * <p>桩是真的 {@link HttpServer}（回环地址 + 随机端口），不 mock HttpClient：要验的正是
 * 「到底发出去了什么字节」，mock 掉 HTTP 层等于把要验的东西替换成了假设。
 */
class WebhookApprovalNotifierTest {

    private static final String SECRET = "test-secret-not-a-real-one";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Stub stub;

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop();
        }
    }

    @Test
    void postsJsonWithSignatureAndHeaders() throws Exception {
        stub = new Stub(200);
        WebhookApprovalNotifier notifier = notifier("http://127.0.0.1:" + stub.port() + "/hook", SECRET,
                List.of("127.0.0.1"), List.of("127.0.0.1"));

        ApprovalNotification notification = notification();
        notifier.notify(notification);

        Recorded recorded = stub.poll();
        assertThat(recorded).isNotNull();
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo("/hook");
        assertThat(recorded.headers())
                .containsEntry("content-type", "application/json; charset=utf-8")
                .containsEntry("x-aiflow-event", "REQUESTED")
                .containsEntry("x-aiflow-approval-id", notification.approvalId().toString());
        assertThat(recorded.headers().get("x-aiflow-signature"))
                .isEqualTo("sha256=" + hmac(SECRET, recorded.body()));

        JsonNode json = MAPPER.readTree(recorded.body());
        assertThat(json.path("version").asInt()).isEqualTo(1);
        assertThat(json.path("event").asText()).isEqualTo("REQUESTED");
        assertThat(json.path("dedupeKey").asText()).isEqualTo(notification.dedupeKey());
        assertThat(json.path("level").asInt()).isEqualTo(2);
        assertThat(json.path("fromLevel").asInt()).isEqualTo(1);
        assertThat(json.path("status").asText()).isEqualTo("PENDING");
        assertThat(json.path("title").asText()).isEqualTo("报销申请：1000 元");
        assertThat(json.path("assignees").get(0).asText()).isEqualTo("alice");
        assertThat(json.path("dueAt").asText()).isEqualTo("2026-01-01T00:10:00Z");
        assertThat(json.path("decidedBy").isNull()).as("未决策时字段在但为 null：接收方形状稳定").isTrue();
    }

    @Test
    void withoutSecretNoSignatureHeaderIsSent() throws Exception {
        stub = new Stub(200);
        WebhookApprovalNotifier notifier = notifier("http://127.0.0.1:" + stub.port() + "/hook", "",
                List.of("127.0.0.1"), List.of("127.0.0.1"));

        notifier.notify(notification());

        Recorded recorded = stub.poll();
        assertThat(recorded).isNotNull();
        assertThat(recorded.headers()).doesNotContainKey("x-aiflow-signature");
        assertThat(notifier.status()).containsEntry("signed", false);
    }

    @Test
    void non2xxIsAnErrorForTheDispatcherToCount() throws Exception {
        stub = new Stub(503);
        WebhookApprovalNotifier notifier = notifier("http://127.0.0.1:" + stub.port() + "/hook", SECRET,
                List.of("127.0.0.1"), List.of("127.0.0.1"));

        assertThatThrownBy(() -> notifier.notify(notification()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("503");
    }

    @Test
    void hostOutsideAllowListNeverGoesOut() throws Exception {
        stub = new Stub(200);
        String url = "http://127.0.0.1:" + stub.port() + "/hook";
        WebhookApprovalNotifier notifier = notifier(url, SECRET, List.of(), List.of("127.0.0.1"));

        assertThat(notifier.enabled()).as("过不了出网护栏就不许投递").isFalse();
        assertThat(notifier.status())
                .containsEntry("configured", true)
                .containsEntry("target", null);
        assertThat((String) notifier.status().get("blocked")).contains("allow-hosts");
        assertThat(stub.recorded).isEmpty();
    }

    @Test
    void privateAddressNeedsExplicitAllowance() throws Exception {
        stub = new Stub(200);
        String url = "http://127.0.0.1:" + stub.port() + "/hook";
        WebhookApprovalNotifier notifier = notifier(url, SECRET, List.of("127.0.0.1"), List.of());

        assertThat(notifier.enabled()).isFalse();
        assertThat((String) notifier.status().get("blocked")).contains("内网");
    }

    @Test
    void blankUrlIsReportedAsNotConfigured() {
        WebhookApprovalNotifier notifier = notifier("", SECRET, List.of("127.0.0.1"), List.of("127.0.0.1"));

        assertThat(notifier.enabled()).isFalse();
        assertThat(notifier.status())
                .containsEntry("configured", false)
                .containsEntry("signed", true);
        assertThat((String) notifier.status().get("blocked")).contains("url");
    }

    @Test
    void disabledChannelStaysVisibleInStatus() {
        ApprovalNotifyProperties properties = new ApprovalNotifyProperties(true, 512, 2000,
                new ApprovalNotifyProperties.Webhook(false, "", 3000, ""));
        WebhookApprovalNotifier notifier = new WebhookApprovalNotifier(properties, MAPPER, guard(List.of(), List.of(), 80));

        assertThat(notifier.enabled()).isFalse();
        assertThat(notifier.channel()).isEqualTo("webhook");
        assertThat(notifier.status()).containsEntry("enabled", false).containsEntry("blocked", null);
    }

    private static WebhookApprovalNotifier notifier(String url, String secret, List<String> allowHosts,
                                                    List<String> allowPrivateHosts) {
        int port = 80;
        int colon = url.lastIndexOf(':');
        if (colon > 0 && url.indexOf('/', colon) > 0) {
            port = Integer.parseInt(url.substring(colon + 1, url.indexOf('/', colon)));
        }
        ApprovalNotifyProperties properties = new ApprovalNotifyProperties(true, 512, 2000,
                new ApprovalNotifyProperties.Webhook(true, url, 3000, secret));
        return new WebhookApprovalNotifier(properties, MAPPER, guard(allowHosts, allowPrivateHosts, port));
    }

    private static SsrfGuard guard(List<String> allowHosts, List<String> allowPrivateHosts, int port) {
        ToolProperties tools = new ToolProperties(false, 5000, 65536, 8, 3, allowHosts, allowPrivateHosts,
                List.of(port), Map.of());
        return new SsrfGuard(tools);
    }

    private static ApprovalNotification notification() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        return new ApprovalNotification("REQUESTED", UUID.randomUUID(), UUID.randomUUID(), "approve", 2, 1,
                "PENDING", "报销申请：1000 元", "提交人：郑振坤", List.of("alice", "bob"), at.plusSeconds(600),
                null, null, null, at);
    }

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private record Recorded(String method, String path, Map<String, String> headers, String body) {
    }

    private static final class Stub {

        private final HttpServer server;
        private final BlockingQueue<Recorded> recorded = new LinkedBlockingQueue<>();

        private Stub(int responseStatus) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                Map<String, String> headers = new TreeMap<>();
                exchange.getRequestHeaders().forEach((name, values) ->
                        headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
                recorded.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        headers, new String(body, StandardCharsets.UTF_8)));
                byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private Recorded poll() throws InterruptedException {
            return recorded.poll(3, TimeUnit.SECONDS);
        }

        private void stop() {
            server.stop(0);
        }
    }
}
