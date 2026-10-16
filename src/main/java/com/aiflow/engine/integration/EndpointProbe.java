package com.aiflow.engine.integration;

import com.aiflow.engine.provider.EndpointHealth;
import com.aiflow.engine.provider.HealthProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * OpenAI 兼容端点的探活：GET {base-url}/models，并在拿到模型清单时核对「要用的那个模型在不在」。
 *
 * <p>状态码怎么判：2xx 视为可用；401/403 视为「服务活着但密钥不对」——那是不可用，必须报出来，
 * 否则云端档位会一直被当成健康档位选中、每次请求都失败；其余 4xx 视为可达（有些网关根本不实现 /models）；
 * 5xx / 连接失败 / 超时视为不可用。
 *
 * <p>为什么要看模型清单：2xx 只回答「服务活着」，不回答「能答」。刚装好 Ollama、模型还没拉下来时，
 * 探活会一路报 up，能力矩阵于是显示「本地对话可用（L2）」，而真实请求必然 404 —— 状态接口把不可用的档位
 * 报成可用，比报错更糟，因为它会让人以为降级没发生。
 *
 * <p>不误判的边界：清单拿不到（非 JSON、没有 data 数组、数组里没有 id、模型名没配）时按「可用」处理 ——
 * 那时我们不知道，不能凭猜判死。但「合法且为空」的清单不是不知道，是明确的「本机没有模型」：
 * 装了 Ollama 却一个模型也没拉，正是这个状态，必须报不可用。
 */
public final class EndpointProbe implements HealthProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final EndpointHealth health;
    private final HttpClient httpClient;
    private final URI modelsUri;
    private final String apiKey;
    private final Duration timeout;
    private final String expectedModel;

    public EndpointProbe(String id, EndpointHealth health, String baseUrl, String apiKey, long timeoutMillis) {
        this(id, health, baseUrl, apiKey, timeoutMillis, "");
    }

    public EndpointProbe(String id, EndpointHealth health, String baseUrl, String apiKey, long timeoutMillis,
                         String expectedModel) {
        this.id = id;
        this.health = health;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.expectedModel = expectedModel == null ? "" : expectedModel.strip();
        this.timeout = Duration.ofMillis(Math.max(1, timeoutMillis));
        String normalized = baseUrl == null ? "" : baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.modelsUri = URI.create(normalized + "/models");
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean probe() {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(modelsUri).timeout(timeout).GET();
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            request = builder.build();
        } catch (IllegalArgumentException e) {
            health.recordProbeFailure("探活请求构造失败");
            return false;
        }
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                String problem = modelProblem(modelIds(response.body()));
                if (problem != null) {
                    health.recordProbeFailure(problem);
                    return false;
                }
                health.recordProbeSuccess();
                return true;
            }
            if (status == 401 || status == 403) {
                health.recordProbeFailure("探活鉴权失败 HTTP " + status);
                return false;
            }
            if (status < 500) {
                health.recordProbeSuccess();
                return true;
            }
            health.recordProbeFailure("探活返回 HTTP " + status);
            return false;
        } catch (IOException e) {
            health.recordProbeFailure("探活失败: 网络/协议错误");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            health.recordProbeFailure("探活被中断");
            return false;
        }
    }

    /**
     * 返回 null 表示「没发现问题」，返回文案表示这一档不可用。
     *
     * <p>{@code ids == null} 是「清单拿不到」：探活的职责是发现明确的故障，不是补充证据，此时不下结论。
     * 空清单则是拿得到的结论 —— 端点在线、但它一个模型都没有，配置的那个当然也没有。
     */
    private String modelProblem(List<String> ids) {
        if (ids == null || expectedModel.isBlank()) {
            return null;
        }
        if (ids.isEmpty()) {
            return "端点在线但模型清单为空：配置的 " + expectedModel + " 尚未部署（拉取后下次探活自动恢复）";
        }
        if (ids.stream().noneMatch(candidate -> matches(candidate, expectedModel))) {
            return "端点在线但模型列表中不含 " + expectedModel;
        }
        return null;
    }

    /**
     * 模型名对齐：按边界符切词后，一侧的「词序列」是另一侧的连续子序列。
     *
     * <p>覆盖三种真实形态：{@code qwen2.5:1.5b} 原样列出；网关把版本写进 id
     * （{@code gpt-4o-mini-2024-07-18}）；网关给 id 加厂商前缀（{@code openai/gpt-4o-mini}），
     * 二者叠加（{@code openai/gpt-4o-mini-2024-07-18}）也认。
     *
     * <p>切词而不是直接 contains，是为了让 {@code gpt-4o-minixyz} 这类「只是恰好以它开头」的 id 不通过；
     * 而 {@code text-embedding-3-small} 与 {@code text-embedding-3-large} 词序列不同，同样不通过。
     * 匹配宽一点只会让端点保持 up（宁可多试一次），匹配松到跨模型就会掩盖真故障。
     */
    static boolean matches(String listedId, String expectedModel) {
        if (listedId.equalsIgnoreCase(expectedModel)) {
            return true;
        }
        List<String> listed = tokens(listedId);
        List<String> expected = tokens(expectedModel);
        return containsRun(listed, expected) || containsRun(expected, listed);
    }

    private static List<String> tokens(String value) {
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[-:/@.]+"))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private static boolean containsRun(List<String> haystack, List<String> needle) {
        if (needle.isEmpty() || needle.size() > haystack.size()) {
            return false;
        }
        for (int start = 0; start + needle.size() <= haystack.size(); start++) {
            if (haystack.subList(start, start + needle.size()).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回 null = 拿不到清单（不下结论）；返回空 List = 清单合法但没有任何模型。
     *
     * <p>「空」有两种真实写法：空数组 {@code {"data":[]}} 与显式 null {@code {"data":null}}
     * （Ollama 在没拉任何模型时用后者）。两者都是服务端明确回答了「我这里什么都没有」，
     * 与「没有 data 字段」（{@code {}}，网关没实现 /models 却返回 200）不是一回事。
     */
    private static List<String> modelIds(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            if (data.isMissingNode()) {
                return null;
            }
            if (data.isNull()) {
                // Ollama 在没拉任何模型时就是这么答的：{"object":"list","data":null}
                return List.of();
            }
            if (!data.isArray()) {
                return null;
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode item : data) {
                String modelId = item.path("id").asText("");
                if (!modelId.isBlank()) {
                    ids.add(modelId);
                }
            }
            // 数组非空却一个 id 都读不出来，说明格式不是我们认识的那种，同样不下结论
            return ids.isEmpty() && !data.isEmpty() ? null : ids;
        } catch (IOException e) {
            // 网关的 /models 返回非 JSON（有的直接把模型名按行返回）时不下结论
            return null;
        }
    }

}
