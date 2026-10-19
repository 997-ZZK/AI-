package com.aiflow.engine.integration;

import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.node.RunCanceledException;
import com.aiflow.engine.provider.ChatImage;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatProvider;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import com.aiflow.engine.provider.EndpointHealth;
import com.aiflow.engine.provider.ProviderCallException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议的 chat provider：POST {base-url}/chat/completions（stream=true，SSE 增量返回）。
 *
 * <p>一个类同时覆盖本地（Ollama /v1）与云端网关：它们的差别只有 base-url、model、api-key 与能力集，
 * 协议完全一致。这也是为什么不给「本地」「云端」各写一个类 —— 那样两份代码会各自长出自己的坑。
 *
 * <p>失败一律转成 {@link ProviderCallException} 并写回 {@link EndpointHealth}：上层凭这两个信号做降级。
 * 线程被中断代表用户取消了这一轮，抛 {@link RunCanceledException}，不算 provider 故障，也不该触发降级重试。
 */
public class OpenAiCompatibleChatProvider implements ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatProvider.class);
    private static final String DONE = "[DONE]";
    private static final String STREAM_TIMEOUT = "chat 流读取超时";
    private static final ScheduledThreadPoolExecutor STREAM_TIMEOUT_EXECUTOR = timeoutExecutor();

    private final String id;
    private final ProviderProperties.Endpoint config;
    private final EndpointHealth health;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI chatUri;
    private final Map<String, Object> extraBody;

    private static ScheduledThreadPoolExecutor timeoutExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "aiflow-chat-stream-timeout");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor)
                Executors.newScheduledThreadPool(1, factory);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public OpenAiCompatibleChatProvider(String id, ProviderProperties.Endpoint config, EndpointHealth health,
                                        ObjectMapper objectMapper) {
        this.id = id;
        this.config = config;
        this.health = health;
        this.objectMapper = objectMapper;
        String baseUrl = config.baseUrl() == null ? "" : config.baseUrl().strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.chatUri = URI.create(baseUrl + "/chat/completions");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMs())).build();
        this.extraBody = EndpointExtraBody.parse(config.extraBody(), objectMapper);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<String> capabilities() {
        return config.capabilities();
    }

    @Override
    public boolean healthy() {
        return health.available();
    }

    @Override
    public String description() {
        // 状态诊断可能进入能力 API；base-url 可能带 query/userinfo 形式的凭证，不能暴露。
        return config.model();
    }

    public EndpointHealth health() {
        return health;
    }

    public ProviderProperties.Endpoint config() {
        return config;
    }

    @Override
    public ChatResponse chat(ChatRequest request, Consumer<String> deltaConsumer) {
        String model = request.model() == null || request.model().isBlank() ? config.model() : request.model();
        if (model == null || model.isBlank()) {
            throw new ProviderCallException(id, "端点 " + id + " 未配置 model");
        }
        String payload = serialize(model, request.messages());
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(chatUri)
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + (config.apiKey() == null ? "" : config.apiKey()))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            // JDK 的 header 校验异常可能带完整值，其中包含配置的凭证。
            throw new ProviderCallException(id, "chat 请求构造失败");
        }
        try {
            long deadlineNanos = deadlineNanos(config.timeoutMs());
            HttpResponse<Stream<String>> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 上游可能在错误正文中回显请求或凭证；不读取，也不向健康状态/快照传播。
                closeQuietly(response.body());
                String message = "chat 调用失败 HTTP " + response.statusCode();
                health.recordFailure(message);
                throw new ProviderCallException(id, message);
            }
            ChatResponse chatResponse = parseStream(response.body(), deltaConsumer, model, deadlineNanos);
            health.recordSuccess();
            return chatResponse;
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new RunCanceledException();
            }
            String message = "chat 调用失败: 网络/协议错误";
            health.recordFailure(message);
            // 原异常的 message/cause 可能包含 URL 或正文，不能作为异常链继续传播。
            throw new ProviderCallException(id, message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        }
    }

    private String serialize(String model, List<ChatMessage> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", true);
        List<Map<String, Object>> payloadMessages = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", contentOf(message));
            payloadMessages.add(item);
        }
        body.put("messages", payloadMessages);
        // 厂商私有字段最后合并：可以覆盖 temperature 这类可选参数；model / messages / stream 已在解析期被拒绝
        body.putAll(extraBody);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new ProviderCallException(id, "请求体序列化失败");
        }
    }

    /**
     * 有图 -> OpenAI 多模态数组（text + image_url 两种 part）；无图 -> 纯字符串。
     */
    private static Object contentOf(ChatMessage message) {
        if (!message.hasImages()) {
            return message.content();
        }
        List<Map<String, Object>> parts = new ArrayList<>(message.images().size() + 1);
        if (!message.content().isEmpty()) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("text", message.content());
            parts.add(text);
        }
        for (ChatImage image : message.images()) {
            Map<String, Object> imageUrl = new LinkedHashMap<>();
            imageUrl.put("url", image.url());
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "image_url");
            part.put("image_url", imageUrl);
            parts.add(part);
        }
        return parts;
    }

    /** 逐行读 SSE：`data: {json}` 片段累加并回调；`data: [DONE]` 结束；`: comment` 与空行跳过。 */
    private ChatResponse parseStream(Stream<String> lines, Consumer<String> deltaConsumer, String fallbackModel,
                                     long deadlineNanos) {
        StringBuilder content = new StringBuilder();
        String responseModel = fallbackModel;
        int promptTokens = 0;
        int completionTokens = 0;
        boolean completed = false;
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timeoutTask = null;
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                timedOut.set(true);
                closeQuietly(lines);
            } else {
                timeoutTask = STREAM_TIMEOUT_EXECUTOR.schedule(() -> {
                    if (timedOut.compareAndSet(false, true)) {
                        closeQuietly(lines);
                    }
                }, remainingNanos, TimeUnit.NANOSECONDS);
            }
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                throwIfInterrupted();
                String raw = iterator.next();
                String line = raw == null ? "" : raw.strip();
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                String data = line.startsWith("data:") ? line.substring(5).strip() : line;
                if (data.isEmpty()) {
                    continue;
                }
                if (DONE.equals(data)) {
                    break;
                }
                JsonNode chunk;
                try {
                    chunk = objectMapper.readTree(data);
                } catch (IOException e) {
                    log.debug("provider={} 忽略无法解析的流片段", id);
                    continue;
                }
                if (chunk == null) {
                    continue;
                }
                if (chunk.hasNonNull("error")) {
                    String message = "chat 流返回错误";
                    health.recordFailure(message);
                    throw new ProviderCallException(id, message);
                }
                if (chunk.hasNonNull("model")) {
                    responseModel = chunk.get("model").asText(responseModel);
                }
                JsonNode usage = chunk.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                    completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                }
                JsonNode choices = chunk.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode choice = choices.get(0);
                String piece = text(choice.path("delta").path("content"));
                if (piece.isEmpty() && content.length() == 0) {
                    piece = text(choice.path("message").path("content"));
                }
                if (!piece.isEmpty()) {
                    content.append(piece);
                    if (deltaConsumer != null) {
                        deltaConsumer.accept(piece);
                    }
                    throwIfInterrupted();
                }
            }
            if (timedOut.get() || deadlineExpired(deadlineNanos)) {
                timedOut.set(true);
                throw streamTimeout();
            }
            throwIfInterrupted();
            completed = true;
        } catch (UncheckedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RunCanceledException();
            }
            if (timedOut.get() || deadlineExpired(deadlineNanos)) {
                throw streamTimeout();
            }
            // 流式响应读到一半链路断了：算 provider 故障（可能只是这一档网络抖），交给路由换下一档
            String message = "chat 流读取失败";
            health.recordFailure(message);
            throw new ProviderCallException(id, message);
        } finally {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            // close 失败也不能把上游异常挂成 suppressed，避免原始报文沿异常图泄露。
            if (!closeQuietly(lines) && completed) {
                String message = "chat 流读取失败";
                health.recordFailure(message);
                throw new ProviderCallException(id, message);
            }
        }
        String reply = content.toString();
        return new ChatResponse(reply, responseModel, promptTokens > 0 ? promptTokens : estimate(reply),
                completionTokens > 0 ? completionTokens : estimate(reply));
    }

    private ProviderCallException streamTimeout() {
        health.recordFailure(STREAM_TIMEOUT);
        return new ProviderCallException(id, STREAM_TIMEOUT);
    }

    private static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new RunCanceledException();
        }
    }

    private static long deadlineNanos(long timeoutMillis) {
        long now = System.nanoTime();
        long duration = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        if (duration <= 0) {
            return now;
        }
        if (duration == Long.MAX_VALUE || (now > 0 && duration > Long.MAX_VALUE - now)) {
            return Long.MAX_VALUE;
        }
        return now + duration;
    }

    private static boolean deadlineExpired(long deadlineNanos) {
        return deadlineNanos != Long.MAX_VALUE && deadlineNanos - System.nanoTime() <= 0;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.isArray() ? node.toString() : node.asText("");
    }

    private static int estimate(String text) {
        return text == null || text.isEmpty() ? 0 : Math.max(1, text.length() / 2);
    }

    private static boolean closeQuietly(Stream<String> lines) {
        try {
            lines.close();
            return true;
        } catch (RuntimeException ignored) {
            // 只反馈关闭是否成功，不传播可能包含报文的异常链。
            return false;
        }
    }

}
