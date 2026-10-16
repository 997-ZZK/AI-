package com.aiflow.engine.approval.notify;

import com.aiflow.engine.tool.SsrfGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * webhook 渠道：把通知 POST 成一段自解释的 JSON，给「审批人不在引擎页面上」的场景用。
 *
 * <p>四个决定是刻意的：
 * <ul>
 *   <li><b>出网走与 HTTP 工具同一份护栏</b>（{@link SsrfGuard}：协议 / 端口 / host 白名单 / 内网地址解析）。
 *       少了这道闸，审批节点就成了「往任意地址发 POST」的开放代理 —— 而审批节点常由业务方在低代码页面
 *       上自己拖出来，攻击面比工具调用更大。</li>
 *   <li><b>每次投递都重新校验</b>：装配时那次只能证明「配置那一刻」安全，域名解析结果会变
 *       （与 {@code McpHttpJsonRpcClient}、{@code HttpToolInvoker} 同口径）。</li>
 *   <li><b>配置问题在装配期暴露，但不让上下文起不来</b>：url 缺失 / 过不了护栏 → 记 WARN，
 *       渠道标记为未就绪（{@link #enabled()} 返回 false，{@link #status()} 写清原因），
 *       其他渠道与审批本身照常工作。</li>
 *   <li><b>不跟随重定向</b>：跟随等于把「白名单校验过的地址」换成「对方指定的地址」。</li>
 * </ul>
 *
 * <p>签名：{@code X-AIFlow-Signature: sha256=hex(HMAC-SHA256(secret, body))}，secret 只从环境变量注入、
 * 不进库不进日志（{@link #status()} 只说「签不签」，不说签的是什么）。接收方按同样方式算一遍即可确认
 * 这条通知确实来自本引擎且没被改过。**不加密内容** —— 需要保密请自己上 HTTPS，别指望这一层。
 */
@Component
public class WebhookApprovalNotifier implements ApprovalNotifier {

    /** 载荷版本：接收方按需 pin 住，避免以后加字段把老接收方打崩。 */
    public static final int SPEC_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(WebhookApprovalNotifier.class);
    private static final int RESPONSE_SNIPPET_BYTES = 256;
    private static final int REASON_LIMIT = 300;

    private final ApprovalNotifyProperties properties;
    private final ObjectMapper objectMapper;
    private final SsrfGuard ssrfGuard;
    private final HttpClient httpClient;
    private final boolean configured;
    private final boolean ready;
    private final String target;
    private final String blockedReason;
    private final boolean signed;

    public WebhookApprovalNotifier(ApprovalNotifyProperties properties, ObjectMapper objectMapper,
                                   SsrfGuard ssrfGuard) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.ssrfGuard = ssrfGuard;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1L, properties.webhook().timeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.signed = properties.webhook().secret() != null && !properties.webhook().secret().isBlank();
        String url = properties.webhook().url();
        this.configured = url != null && !url.isBlank();
        String readyTarget = null;
        String blocked = null;
        if (properties.webhook().enabled() && !configured) {
            blocked = "webhook 已开启但 url 为空（aiflow.approval.notify.webhook.url）";
            log.warn("审批通知 webhook 未配置：{} —— 该渠道不会投递（状态看 /api/v1/approvals/notify）", blocked);
        } else if (configured) {
            try {
                URI uri = ssrfGuard.validate(url);
                readyTarget = describe(uri);
            } catch (RuntimeException e) {
                blocked = describe(e);
                log.warn("审批通知 webhook 被出网护栏拦下（渠道不会投递）: {} —— 需要放行请改 aiflow.tools.allow-hosts / "
                        + "allow-private-hosts / allow-ports（与 HTTP 工具共用同一份白名单）", blocked);
            }
        }
        this.target = readyTarget;
        this.ready = readyTarget != null;
        this.blockedReason = blocked;
    }

    @Override
    public String channel() {
        return "webhook";
    }

    @Override
    public boolean enabled() {
        return properties.webhook().enabled() && ready;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled());
        status.put("configured", configured);
        status.put("target", target);
        status.put("signed", signed);
        status.put("timeoutMs", properties.webhook().timeoutMs());
        status.put("specVersion", SPEC_VERSION);
        status.put("blocked", blockedReason);
        return status;
    }

    @Override
    public void notify(ApprovalNotification notification) throws IOException, InterruptedException {
        URI uri = ssrfGuard.validate(properties.webhook().url());
        byte[] body = objectMapper.writeValueAsBytes(payload(notification));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(1L, properties.webhook().timeoutMs())))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json, */*;q=0.5")
                .header("X-AIFlow-Event", String.valueOf(notification.event()))
                .header("X-AIFlow-Approval-Id", String.valueOf(notification.approvalId()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (signed) {
            builder.header("X-AIFlow-Signature", "sha256=" + hmacHex(properties.webhook().secret(), body));
        }
        HttpResponse<InputStream> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int httpStatus = response.statusCode();
        String snippet;
        try (InputStream stream = response.body()) {
            byte[] bytes = stream.readNBytes(RESPONSE_SNIPPET_BYTES);
            snippet = oneLine(new String(bytes, StandardCharsets.UTF_8));
        }
        if (httpStatus < 200 || httpStatus >= 300) {
            throw new IOException("webhook 返回非 2xx: " + httpStatus + "，响应片段: " + snippet);
        }
        log.debug("审批通知已投递 {} approval={} event={} -> {}", httpStatus, notification.approvalId(),
                notification.event(), target);
    }

    /**
     * 手写映射而不是直接序列化 {@link ApprovalNotification}：线上 ObjectMapper 的配置（时间格式、命名策略）
     * 是部署者可改的，而**对外协议的形状不该跟着 Spring 配置漂**。顺手把 null 字段留着不删 ——
     * 接收方拿到的是稳定形状，少写一堆判空。
     */
    private Map<String, Object> payload(ApprovalNotification notification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", SPEC_VERSION);
        body.put("event", notification.event());
        body.put("dedupeKey", notification.dedupeKey());
        body.put("approvalId", text(notification.approvalId()));
        body.put("runId", text(notification.runId()));
        body.put("nodeId", notification.nodeId());
        body.put("level", notification.level());
        body.put("fromLevel", notification.fromLevel());
        body.put("status", notification.status());
        body.put("title", notification.title());
        body.put("detail", notification.detail());
        body.put("assignees", notification.assignees() == null ? List.of() : notification.assignees());
        body.put("dueAt", text(notification.dueAt()));
        body.put("decidedBy", notification.decidedBy());
        body.put("comment", notification.comment());
        body.put("decidedAt", text(notification.decidedAt()));
        body.put("at", text(notification.at()));
        return body;
    }

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用: " + e.getMessage(), e);
        }
    }

    /** host + 端口，**不含路径** —— 路径里可能塞着接收方自己的 token，不该出现在状态接口里。 */
    private static String describe(URI uri) {
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return uri.getScheme() + "://" + uri.getHost() + ":" + port;
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() <= REASON_LIMIT ? message : message.substring(0, REASON_LIMIT) + "...";
    }

    /** 日志与错误消息里不留换行：响应体是从外面来的，多行内容能把日志伪造出一行假记录。 */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length() && builder.length() < RESPONSE_SNIPPET_BYTES; i++) {
            char c = text.charAt(i);
            builder.append(Character.isISOControl(c) ? ' ' : c);
        }
        return builder.toString().strip();
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }
}
