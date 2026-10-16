package com.aiflow.engine.integration;

import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.RetrievalException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的 embedding 适配器：/embeddings。
 * 云厂商（OpenAI 兼容网关）、本地 vLLM、Ollama 都吃同一份契约，换 provider 只改配置。
 */
public class HttpEmbeddingProvider implements EmbeddingProvider {

    private final RetrievalProperties.Embedding config;
    private final RestClient restClient;

    public HttpEmbeddingProvider(RetrievalProperties.Embedding config) {
        this.config = config;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(config.timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(config.timeoutMs()));
        this.restClient = RestClient.builder().baseUrl(config.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public String id() {
        return "http-embedding:" + config.model();
    }

    @Override
    public int dimensions() {
        return config.dimensions();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of("model", config.model(), "input", texts);
        try {
            EmbeddingResponse response = restClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (config.apiKey() != null && !config.apiKey().isBlank()) {
                            headers.setBearerAuth(config.apiKey());
                        }
                    })
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, httpResponse) -> {
                        // 默认错误处理会收集上游正文；它可能回显请求或凭证，不能进入异常链。
                        throw new RetrievalException("embedding 服务调用失败 HTTP " + httpResponse.getStatusCode().value());
                    })
                    .body(EmbeddingResponse.class);
            if (response == null || response.data() == null || response.data().size() != texts.size()) {
                throw new RetrievalException("embedding 服务返回条数与请求不一致: "
                        + (response == null || response.data() == null ? 0 : response.data().size())
                        + " != " + texts.size());
            }
            List<float[]> vectors = new ArrayList<>(response.data().size());
            for (EmbeddingItem item : response.data()) {
                vectors.add(toVector(item));
            }
            return vectors;
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new RetrievalException("embedding 服务调用失败: 网络/协议错误");
        } catch (IllegalArgumentException e) {
            // HTTP header 校验异常可能携带完整 Authorization 值，不传播原始异常。
            throw new RetrievalException("embedding 请求构造失败");
        }
    }

    private float[] toVector(EmbeddingItem item) {
        if (item == null || item.embedding() == null || item.embedding().isEmpty()) {
            throw new RetrievalException("embedding 服务返回空向量");
        }
        float[] vector = new float[item.embedding().size()];
        for (int index = 0; index < vector.length; index++) {
            Double value = item.embedding().get(index);
            vector[index] = value == null ? 0f : value.floatValue();
        }
        return vector;
    }

    record EmbeddingResponse(List<EmbeddingItem> data, String model) {
    }

    record EmbeddingItem(List<Double> embedding, Integer index) {
    }
}
