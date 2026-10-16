/*
 * 本地审批 webhook 接收方（联调用，不参与打包）：收 POST，打一行 JSON 摘要。
 *
 *   java script/dev-approval-webhook-receiver.java 19099 [secret] [日志文件]
 *
 * 引擎侧要配三件事（缺一个就收不到）：
 *   AIFLOW_APPROVAL_WEBHOOK_ENABLED=true
 *   AIFLOW_APPROVAL_WEBHOOK_URL=http://127.0.0.1:19099/approval
 *   AIFLOW_APPROVAL_WEBHOOK_SECRET=<和这里传的 secret 一样>      # 可选，给了就验签
 * 另外 webhook 出网要过与 HTTP 工具共用的一份白名单：
 *   AIFLOW_TOOLS_ALLOW_HOSTS=127.0.0.1
 *   AIFLOW_TOOLS_ALLOW_PRIVATE_HOSTS=127.0.0.1
 *   AIFLOW_TOOLS_ALLOW_PORTS=80,443,19099
 *
 * 日志文件默认 target/approval-webhook-received.jsonl，一行一条，便于脚本断言。
 */
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class DevApprovalWebhookReceiver {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 19099;
        String secret = args.length > 1 ? args[1] : "";
        Path log = Path.of(args.length > 2 ? args[2] : "target/approval-webhook-received.jsonl");
        Files.createDirectories(log.toAbsolutePath().getParent());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/approval", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
            String body = new String(raw, StandardCharsets.UTF_8);
            String signature = headers.getOrDefault("x-aiflow-signature", "");
            String verdict = secret.isBlank() ? "unsigned"
                    : ("sha256=" + hmac(secret, raw)).equals(signature) ? "valid" : "INVALID";

            String line = "{\"at\":\"" + Instant.now() + "\",\"event\":\""
                    + header(headers, body, "x-aiflow-event", "\"event\"") + "\",\"approvalId\":\""
                    + header(headers, body, "x-aiflow-approval-id", "\"approvalId\"") + "\",\"signature\":\""
                    + verdict + "\",\"body\":" + body + "}";
            System.out.println(line);
            Files.writeString(log, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        System.out.println("webhook 接收方已启动: http://127.0.0.1:" + port + "/approval（日志 " + log + "）");
    }

    /** 请求头优先，没有就从 body 里抠（两个来源都留，排障时不用猜是哪一侧的问题）。 */
    private static String header(Map<String, String> headers, String body, String name, String jsonField) {
        String value = headers.get(name);
        if (value != null && !value.isBlank()) {
            return value;
        }
        int at = body.indexOf(jsonField);
        if (at < 0) {
            return "?";
        }
        int start = body.indexOf('"', at + jsonField.length() + 1);
        int end = start < 0 ? -1 : body.indexOf('"', start + 1);
        return start < 0 || end < 0 ? "?" : body.substring(start + 1, end);
    }

    private static String hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private DevApprovalWebhookReceiver() {
    }
}
