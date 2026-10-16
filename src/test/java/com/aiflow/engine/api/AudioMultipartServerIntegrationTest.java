package com.aiflow.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.aiflow.engine.api.AudioApiTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用真正的嵌入 Servlet 解析 multipart；MockMvc 无法证明容器上传限额。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiflow.audio.enabled=true", "aiflow.audio.mode=mock",
        "spring.servlet.multipart.max-file-size=10MB", "spring.servlet.multipart.max-request-size=12MB"
})
class AudioMultipartServerIntegrationTest {

    private static final Path MULTIPART_DIRECTORY = Path.of("target", "m4-3d", "d1-multipart-test").toAbsolutePath();
    private static final String BOUNDARY = "aiflow-d1-synthetic-upload";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @DynamicPropertySource
    static void temporaryMultipartLocation(DynamicPropertyRegistry registry) throws IOException {
        Files.createDirectories(MULTIPART_DIRECTORY);
        registry.add("spring.servlet.multipart.location", MULTIPART_DIRECTORY::toString);
    }

    @Test
    void realMultipartParserTranscribesAndCleansItsRequestTemporaryFile() throws Exception {
        long before = temporaryFileCount();
        HttpResponse<byte[]> response = upload(TRANSCRIPTIONS, shortWav());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = mapper.readTree(response.body()).path("data");
        assertThat(data.path("durationMs").asLong()).isEqualTo(100);
        assertThat(data.path("model").asText()).isEqualTo("mock-asr-1");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        // 容器自动执行请求清理，测试不删除目录或既有文件。
        awaitRequestCleanup(before);
    }

    @Test
    void servletFileLimitIs413ForAudioAnd400ForExistingUploads() throws Exception {
        long before = temporaryFileCount();
        byte[] overServletLimit = new byte[10 * 1024 * 1024 + 1];
        HttpResponse<byte[]> audio = upload(TRANSCRIPTIONS, overServletLimit);
        assertThat(audio.statusCode()).isEqualTo(413);
        assertThat(audio.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(mapper.readTree(audio.body()).path("code").asInt()).isEqualTo(41300);
        awaitRequestCleanup(before);
        HttpResponse<byte[]> attachment = upload("/api/v1/attachments", overServletLimit);
        assertThat(attachment.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(attachment.body()).path("code").asInt()).isEqualTo(40000);
        awaitRequestCleanup(before);
        HttpResponse<byte[]> document = upload("/api/v1/kbs/d1-synthetic/documents/upload", overServletLimit);
        assertThat(document.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(document.body()).path("code").asInt()).isEqualTo(40000);
        awaitRequestCleanup(before);
    }

    @Test
    void applicationAudioLimitIsEnforcedBelowServletLimit() throws Exception {
        long before = temporaryFileCount();
        HttpResponse<byte[]> response = upload(TRANSCRIPTIONS, new byte[5 * 1024 * 1024 + 1]);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(mapper.readTree(response.body()).path("code").asInt()).isEqualTo(41300);
        awaitRequestCleanup(before);
    }

    @Test
    void servletTotalRequestLimitIs413EvenWhenIndividualPartsFitFileLimit() throws Exception {
        long before = temporaryFileCount();
        RawResponse response = declaredOversizeRequest();
        assertThat(response.status()).isEqualTo(413);
        assertThat(response.headers().stream().map(String::toLowerCase)
                .anyMatch(header -> header.startsWith("content-type: application/json"))).isTrue();
        assertThat(mapper.readTree(decodeBody(response)).path("code").asInt()).isEqualTo(41300);
        awaitRequestCleanup(before);
    }

    /**
     * 先只发送声明过大的请求头，避免客户端在容器已经拒绝请求后继续写大 body，
     * 从而把真实的 413 变成 Windows loopback 的连接重置。
     */
    private RawResponse declaredOversizeRequest() throws IOException {
        String headers = "POST " + TRANSCRIPTIONS + " HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Connection: close\r\n"
                + "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n"
                + "Content-Length: " + (12L * 1024 * 1024 + 1) + "\r\n"
                + "Expect: 100-continue\r\n\r\n";
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] response = socket.getInputStream().readAllBytes();
            String raw = new String(response, StandardCharsets.ISO_8859_1);
            int responseStart = raw.lastIndexOf("HTTP/1.1 ");
            assertThat(responseStart).isGreaterThanOrEqualTo(0);
            raw = raw.substring(responseStart);
            int separator = raw.indexOf("\r\n\r\n");
            assertThat(separator).isGreaterThan(0);
            String head = raw.substring(0, separator);
            String body = raw.substring(separator + 4);
            String[] lines = head.split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            return new RawResponse(status, java.util.Arrays.asList(lines).subList(1, lines.length), body);
        }
    }

    private static String decodeBody(RawResponse response) {
        boolean chunked = response.headers().stream()
                .anyMatch(header -> header.toLowerCase().startsWith("transfer-encoding: chunked"));
        if (!chunked) {
            return response.body();
        }
        String raw = response.body();
        StringBuilder decoded = new StringBuilder();
        int offset = 0;
        while (offset < raw.length()) {
            int lineEnd = raw.indexOf("\r\n", offset);
            if (lineEnd < 0) {
                break;
            }
            int length = Integer.parseInt(raw.substring(offset, lineEnd).trim(), 16);
            offset = lineEnd + 2;
            if (length == 0) {
                break;
            }
            decoded.append(raw, offset, Math.min(offset + length, raw.length()));
            offset += length + 2;
        }
        return decoded.toString();
    }

    private HttpResponse<byte[]> upload(String path, byte[] audio) throws Exception {
        return upload(path, new byte[][]{audio}, false);
    }

    private HttpResponse<byte[]> upload(String path, byte[][] parts, boolean expectContinue) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int index = 0; index < parts.length; index++) {
            String partName = index == 0 ? "file" : "extra";
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + partName
                    + "\"; filename=\"sample.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            body.write(parts[index]);
            body.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .expectContinue(expectContinue)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Accept", "audio/wav, application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private static long temporaryFileCount() throws IOException {
        try (var paths = Files.list(MULTIPART_DIRECTORY)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static void awaitRequestCleanup(long before) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (temporaryFileCount() == before) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(temporaryFileCount()).isEqualTo(before);
    }

    private record RawResponse(int status, java.util.List<String> headers, String body) {
    }
}
