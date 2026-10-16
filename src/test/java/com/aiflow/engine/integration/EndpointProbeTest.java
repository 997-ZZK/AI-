package com.aiflow.engine.integration;

import com.aiflow.engine.provider.EndpointHealth;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointProbeTest {

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok/models", exchange -> respond(exchange, 200, "{}"));
        server.createContext("/auth/models", exchange -> respond(exchange, 401, "{}"));
        server.createContext("/broken/models", exchange -> respond(exchange, 503, "{}"));
        server.createContext("/listed/models", exchange -> respond(exchange, 200,
                "{\"data\":[{\"id\":\"qwen2.5:1.5b\"},{\"id\":\"bge-m3\"}]}"));
        server.createContext("/missing/models", exchange -> respond(exchange, 200,
                "{\"data\":[{\"id\":\"llama3:8b\"},{\"id\":\"bge-m3\"}]}"));
        server.createContext("/versioned/models", exchange -> respond(exchange, 200,
                "{\"data\":[{\"id\":\"openai/gpt-4o-mini-2024-07-18\"}]}"));
        server.createContext("/plain/models", exchange -> respond(exchange, 200, "qwen2.5:1.5b\nbge-m3\n"));
        server.createContext("/empty/models", exchange -> respond(exchange, 200, "{\"data\":[]}"));
        server.createContext("/nulldata/models", exchange -> respond(exchange, 200, "{\"object\":\"list\",\"data\":null}"));
        server.createContext("/idless/models", exchange -> respond(exchange, 200, "{\"data\":[{\"object\":\"model\"}]}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void twoHundredKeepsEndpointUp() {
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);

        assertThat(new EndpointProbe("local", health, baseUrl + "/ok", "", 2_000).probe()).isTrue();
        assertThat(health.snapshot().state()).isEqualTo("up");
        assertThat(health.snapshot().lastProbeAt()).isNotNull();
    }

    @Test
    void unauthorizedCountsAsUnavailable() {
        EndpointHealth health = new EndpointHealth("cloud", true, 3, 1_000);

        assertThat(new EndpointProbe("cloud", health, baseUrl + "/auth", "bad-key", 2_000).probe()).isFalse();
        assertThat(health.snapshot().lastError()).contains("401");
    }

    @Test
    void serverErrorCountsAsUnavailable() {
        EndpointHealth health = new EndpointHealth("cloud", true, 3, 1_000);

        assertThat(new EndpointProbe("cloud", health, baseUrl + "/broken", "", 2_000).probe()).isFalse();
        assertThat(health.snapshot().lastError()).contains("503");
    }

    @Test
    void keepsEndpointUpWhenTheListedModelsContainTheOneWeConfigured() {
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);

        assertThat(new EndpointProbe("local", health, baseUrl + "/listed", "", 2_000, "qwen2.5:1.5b").probe())
                .isTrue();
        assertThat(health.snapshot().state()).isEqualTo("up");
    }

    @Test
    void countsEndpointUnavailableWhenTheConfiguredModelIsMissing() {
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);

        assertThat(new EndpointProbe("local", health, baseUrl + "/missing", "", 2_000, "qwen2.5:1.5b").probe())
                .as("服务活着但没这个模型：真实请求必然 404，不该报成可用档位")
                .isFalse();
        assertThat(health.snapshot().state()).isEqualTo("down");
        assertThat(health.snapshot().lastError()).isEqualTo("端点在线但模型列表中不含 qwen2.5:1.5b")
                .doesNotContain("llama3:8b", "bge-m3");
    }

    @Test
    void acceptsVersionedAndPrefixedModelIds() {
        EndpointHealth health = new EndpointHealth("cloud", true, 3, 1_000);

        assertThat(new EndpointProbe("cloud", health, baseUrl + "/versioned", "", 2_000, "gpt-4o-mini").probe())
                .as("网关常把版本写进 id、或加厂商前缀，这类差异要认")
                .isTrue();
    }

    @Test
    void staysUpWhenTheModelListCannotBeParsed() {
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);

        assertThat(new EndpointProbe("local", health, baseUrl + "/plain", "", 2_000, "qwen2.5:1.5b").probe())
                .as("清单格式不认识时不下结论：宁可多试一次，也别把健康档位判死")
                .isTrue();
    }

    @Test
    void countsEndpointUnavailableWhenTheModelListIsEmpty() {
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);

        assertThat(new EndpointProbe("local", health, baseUrl + "/empty", "", 2_000, "qwen2.5:1.5b").probe())
                .as("装了 Ollama 却一个模型都没拉：服务活着、清单合法且为空，真实请求必然 404")
                .isFalse();
        assertThat(health.snapshot().state()).isEqualTo("down");
        assertThat(health.snapshot().lastError()).contains("模型清单为空").contains("qwen2.5:1.5b");
    }

    @Test
    void countsEndpointUnavailableWhenTheListComesBackAsNull() {
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);

        assertThat(new EndpointProbe("local", health, baseUrl + "/nulldata", "", 2_000, "qwen2.5:1.5b").probe())
                .as("Ollama 没拉模型时返回 {\"data\":null}，那是明确的「一个都没有」，不是「不知道」")
                .isFalse();
        assertThat(health.snapshot().lastError()).contains("模型清单为空");
    }

    @Test
    void keepsEndpointUpWhenNoModelNameIsConfigured() {
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);

        assertThat(new EndpointProbe("local", health, baseUrl + "/empty", "", 2_000).probe())
                .as("没配模型名就无从核对，探活只回答「服务活着」")
                .isTrue();
    }

    @Test
    void staysUpWhenTheListedEntriesCarryNoIds() {
        EndpointHealth health = new EndpointHealth("cloud", true, 3, 1_000);

        assertThat(new EndpointProbe("cloud", health, baseUrl + "/idless", "", 2_000, "gpt-4o-mini").probe())
                .as("数组非空却读不出任何 id：格式不认识，按「不知道」处理而不是判死")
                .isTrue();
    }

    @Test
    void modelNameMatchingToleratesSuffixesAndPrefixesButNotCoincidences() {
        assertThat(EndpointProbe.matches("qwen2.5:1.5b", "qwen2.5:1.5b")).isTrue();
        assertThat(EndpointProbe.matches("gpt-4o-mini-2024-07-18", "gpt-4o-mini")).isTrue();
        assertThat(EndpointProbe.matches("openai/gpt-4o-mini", "gpt-4o-mini")).isTrue();
        assertThat(EndpointProbe.matches("openai/gpt-4o-mini-2024-07-18", "gpt-4o-mini")).isTrue();
        assertThat(EndpointProbe.matches("bge-m3:latest", "bge-m3")).isTrue();
        assertThat(EndpointProbe.matches("gpt-4o-minixyz", "gpt-4o-mini")).isFalse();
        assertThat(EndpointProbe.matches("text-embedding-3-large", "text-embedding-3-small")).isFalse();
        assertThat(EndpointProbe.matches("llama3:8b", "qwen2.5:1.5b")).isFalse();
    }

    @Test
    void unreachablePortCountsAsUnavailable() {
        EndpointHealth health = new EndpointHealth("local", true, 3, 1_000);

        assertThat(new EndpointProbe("local", health, "http://127.0.0.1:1", "", 1_000).probe()).isFalse();
        assertThat(health.snapshot().state()).isEqualTo("down");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
