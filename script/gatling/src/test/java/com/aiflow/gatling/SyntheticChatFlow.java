package com.aiflow.gatling;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.UUID;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.sse;
import static io.gatling.javaapi.http.HttpDsl.status;

/** Shared S0/S1 flow; no dependency on the engine application or real providers. */
final class SyntheticChatFlow {
    private static final Object EVIDENCE_LOCK = new Object();
    private final String stage;
    private final int users;
    private final int rounds;
    private final String prefix;
    private final Path evidenceFile;
    private final Duration eventTimeout;

    SyntheticChatFlow(String stage, int defaultUsers, int defaultRounds) {
        this.stage = stage;
        this.users = positiveProperty("load.users", defaultUsers);
        this.rounds = positiveProperty("load.rounds", defaultRounds);
        this.eventTimeout = Duration.ofSeconds(positiveProperty("load.eventTimeoutSeconds", 30));
        this.prefix = System.getProperty("load.prefix", "m43c-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        if (!prefix.matches("[A-Za-z0-9-]{1,40}")) {
            throw new IllegalArgumentException("load.prefix must contain 1-40 letters, digits or hyphens");
        }
        Path reportRoot = repositoryRoot().resolve("target/m4-3c").toAbsolutePath().normalize();
        String requestedFile = System.getProperty("load.evidenceFile");
        this.evidenceFile = requestedFile == null
                ? reportRoot.resolve(stage + "-" + prefix + "-evidence.ndjson")
                : Path.of(requestedFile).toAbsolutePath().normalize();
        if (!evidenceFile.startsWith(reportRoot) || !evidenceFile.getFileName().toString().endsWith(".ndjson")) {
            throw new IllegalArgumentException("load.evidenceFile must be an NDJSON file under " + reportRoot);
        }
        try {
            Files.createDirectories(evidenceFile.getParent());
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create synthetic load evidence directory", failure);
        }
    }

    int users() {
        return users;
    }

    HttpProtocolBuilder protocol() {
        String baseUrl = System.getProperty("load.baseUrl", "http://localhost:8082");
        if (!baseUrl.matches("http://(localhost|127\\.0\\.0\\.1):[0-9]+")) {
            throw new IllegalArgumentException("Only a local validation origin is allowed");
        }
        return http.baseUrl(baseUrl)
                .acceptHeader("application/json")
                .contentTypeHeader("application/json");
    }

    ScenarioBuilder buildScenario() {
        return scenario("m4-3c-" + stage + "-synthetic-chat")
                .exec(session -> session.set("externalId", prefix + "-u" + session.userId()))
                .exec(http("create-user")
                        .post("/api/v1/users")
                        .body(StringBody("{\"externalId\":\"#{externalId}\",\"displayName\":\"Gatling synthetic user\"}"))
                        .check(status().is(200), jsonPath("$.data.id").saveAs("userId")))
                .exitHereIfFailed()
                .exec(http("create-session")
                        .post("/api/v1/sessions")
                        .body(StringBody("{\"userId\":\"#{userId}\",\"title\":\"#{externalId}\",\"workflowName\":\"basic-chat\"}"))
                        .check(status().is(200), jsonPath("$.data.id").saveAs("sessionId")))
                .exitHereIfFailed()
                .repeat(rounds, "roundIndex").on(round());
    }

    private ChainBuilder round() {
        return exec(session -> session
                .removeAll("runId", "ttftMs", "runMs", "terminalType", "terminalStatus", "snapshotStatus", "terminalEpochMs")
                .set("round", session.getInt("roundIndex") + 1)
                .set("clientMsgId", prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20))
                .set("submittedEpochMs", System.currentTimeMillis())
                .set("submittedNano", System.nanoTime()))
                .exec(http("submit-message")
                        .post("/api/v1/sessions/#{sessionId}/messages")
                        .body(StringBody("{\"clientMsgId\":\"#{clientMsgId}\",\"content\":\"Gatling synthetic round #{round}\"}"))
                        .check(status().is(200), jsonPath("$.data.status").is("QUEUED"),
                                jsonPath("$.data.runId").saveAs("runId")))
                .doIf(session -> !session.isFailed()).then(
                        exec(sse("run-events")
                                .get("/api/v1/runs/#{runId}/events")
                                .header("Accept", "text/event-stream")
                                .await(eventTimeout)
                                .on(sse.checkMessage("first-delta")
                                                .matching(jsonPath("$.event").is("DELTA"),
                                                        jsonPath("$.data.data.text").transform(text -> !text.isEmpty()).is(true))
                                                .check(jsonPath("$.data.data.text").exists())
                                                .postCheck(session -> session.set("ttftMs", elapsedMs(session))),
                                        sse.checkMessage("terminal")
                                                .matching(jsonPath("$.event").in("RUN_SUCCEEDED", "RUN_DEGRADED", "RUN_FAILED", "RUN_CANCELED"))
                                                .check(jsonPath("$.event").saveAs("terminalType"),
                                                        jsonPath("$.data.data.status").saveAs("terminalStatus"),
                                                        jsonPath("$.event").is("RUN_SUCCEEDED"),
                                                        jsonPath("$.data.data.status").is("SUCCEEDED"))
                                                .postCheck(session -> session.set("runMs", elapsedMs(session))
                                                        .set("terminalEpochMs", System.currentTimeMillis()))))
                                .exec(sse("run-events").close()))
                .doIf(session -> session.contains("runId")).then(
                        exec(http("run-snapshot")
                                .get("/api/v1/runs/#{runId}")
                                .check(status().is(200), jsonPath("$.data.status").saveAs("snapshotStatus"),
                                        jsonPath("$.data.status").is("SUCCEEDED"))))
                .exec(this::writeEvidence)
                .exitHereIfFailed();
    }

    private Session writeEvidence(Session session) {
        boolean failed = session.isFailed() || !session.contains("ttftMs") || !session.contains("runMs")
                || !"RUN_SUCCEEDED".equals(value(session, "terminalType"))
                || !"SUCCEEDED".equals(value(session, "snapshotStatus"));
        String line = "{\"stage\":" + json(stage) + ",\"prefix\":" + json(prefix)
                + ",\"vu\":" + session.userId() + ",\"externalId\":" + json(value(session, "externalId"))
                + ",\"userId\":" + json(value(session, "userId")) + ",\"sessionId\":" + json(value(session, "sessionId"))
                + ",\"runId\":" + json(value(session, "runId")) + ",\"clientMsgId\":" + json(value(session, "clientMsgId"))
                + ",\"round\":" + session.getInt("round") + ",\"ttftMs\":" + json(value(session, "ttftMs"))
                + ",\"submittedEpochMs\":" + json(value(session, "submittedEpochMs"))
                + ",\"terminalEpochMs\":" + json(value(session, "terminalEpochMs"))
                + ",\"runMs\":" + json(value(session, "runMs")) + ",\"terminalType\":" + json(value(session, "terminalType"))
                + ",\"terminalStatus\":" + json(value(session, "terminalStatus"))
                + ",\"snapshotStatus\":" + json(value(session, "snapshotStatus")) + ",\"failed\":" + failed + "}\n";
        synchronized (EVIDENCE_LOCK) {
            try {
                Files.writeString(evidenceFile, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot append synthetic load evidence", failure);
            }
        }
        return failed ? session.markAsFailed() : session;
    }

    private static double elapsedMs(Session session) {
        return (System.nanoTime() - session.getLong("submittedNano")) / 1_000_000.0;
    }

    private static Object value(Session session, String key) {
        return session.contains(key) ? session.get(key) : null;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private static int positiveProperty(String name, int defaultValue) {
        int value = Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Path repositoryRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (path != null) {
            if (Files.isRegularFile(path.resolve("docs/architecture.md")) && Files.isRegularFile(path.resolve("pom.xml"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Run Gatling from the AIFlowEngine repository or its subdirectories");
    }
}
