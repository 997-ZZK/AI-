package com.aiflow.engine.tool;

import com.aiflow.engine.config.ToolProperties;
import com.aiflow.engine.node.RunCanceledException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

/**
 * HTTP 工具调用：不发重定向、不带 host 头、响应体有上限。
 *
 * <p>三条都是护栏的一部分，不是随手加的：
 * <ul>
 *   <li>不跟随重定向：跟随等于把「白名单校验过的 URL」换成「对方指定的 URL」，SSRF 防线在第一次 302 就失效了</li>
 *   <li>Host 头由 HttpClient 自己写：配置里塞 Host 会被 JVM 拒绝，这里翻译成人话而不是抛 IllegalArgumentException</li>
 *   <li>响应体截断：大响应灌进节点快照会把 node_runs 撑爆，也会把内存吃光</li>
 * </ul>
 */
@Component
public class HttpToolInvoker {

    private final ToolProperties properties;
    private final HttpClient httpClient;

    public HttpToolInvoker(ToolProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Response invoke(URI uri, String method, Map<String, String> headers, String body) {
        long startedAt = System.nanoTime();
        HttpRequest.BodyPublisher publisher = body == null || body.isBlank()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
                .method(method, publisher);
        if (body != null && !body.isBlank()) {
            builder.header("Content-Type", "application/json");
        }
        try {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getValue() != null && !header.getValue().isBlank()) {
                    builder.header(header.getKey(), header.getValue());
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ToolBlockedException("工具 header 非法: " + e.getMessage()
                    + "（Host / Content-Length 等由 JVM 接管，不需要也不允许配置）");
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes = stream.readNBytes(properties.maxBodyBytes() + 1);
            }
            boolean truncated = bytes.length > properties.maxBodyBytes();
            if (truncated) {
                bytes = Arrays.copyOf(bytes, properties.maxBodyBytes());
            }
            return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8), truncated,
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        } catch (IOException e) {
            throw new ToolInvocationException("工具调用失败（网络层）: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        }
    }

    public record Response(int httpStatus, String body, boolean truncated, long durationMs) {

        public boolean successful() {
            return httpStatus >= 200 && httpStatus < 300;
        }
    }
}