package com.aiflow.gatling;

import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.SseMessageCheck;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.listFeeder;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.sse;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * S3a/S3b: read-only idle SSE connections for existing terminal runs.
 * load.runsFile is an absolute target/m4-3c CSV with runId,lastSeq columns.
 * Its lastSeq values must come from a fresh Redis event verification snapshot.
 * load.evidenceFile is an absolute target/m4-3c NDJSON path for per-VU observations.
 * External sampling must verify live connection/reader counts and final cleanup.
 */
public final class IdleSseSimulation extends Simulation {
    private static final long MINIMUM_OBSERVED_NANOS = Duration.ofSeconds(60).toNanos();
    private static final Object EVIDENCE_LOCK = new Object();
    private final String topology;
    private final Path evidenceFile;
    private final int rampSeconds;

    public IdleSseSimulation() {
        int users = Integer.parseInt(System.getProperty("load.users", "1000"));
        if (users < 1) throw new IllegalArgumentException("load.users must be positive");
        this.rampSeconds = Integer.parseInt(System.getProperty("load.rampSeconds", "0"));
        if (rampSeconds < 0 || rampSeconds > 300) {
            throw new IllegalArgumentException("load.rampSeconds must be 0-300");
        }
        this.topology = System.getProperty("load.topology", "same-run");
        if (!topology.equals("same-run") && !topology.equals("different-runs")) {
            throw new IllegalArgumentException("load.topology must be same-run or different-runs");
        }
        List<Map<String, Object>> rows = readRunsFile();
        if (topology.equals("same-run") && rows.size() != 1) {
            throw new IllegalArgumentException("same-run requires exactly one CSV row");
        }
        if (topology.equals("different-runs") && rows.size() < users) {
            throw new IllegalArgumentException("different-runs requires at least load.users distinct CSV rows");
        }
        this.evidenceFile = evidenceFilePath();
        FeederBuilder<Object> feeder = topology.equals("same-run")
                ? listFeeder(rows).circular() : listFeeder(rows).queue();

        HttpProtocolBuilder protocol = http.baseUrl(localBaseUrl()).acceptHeader("application/json");
        ScenarioBuilder idle = scenario("m4-3c-s3-" + topology + "-idle-sse")
                .feed(feeder)
                .exec(http("existing-terminal-run")
                        .get("/api/v1/runs/#{runId}")
                        .check(status().is(200), jsonPath("$.data.runId").is(session -> session.getString("runId")),
                                jsonPath("$.data.status").in("SUCCEEDED", "DEGRADED", "FAILED", "CANCELED")))
                .doIf(session -> !session.isFailed()).then(exec(sse("idle-events")
                        .get("/api/v1/runs/#{runId}/events")
                        .header("Accept", "text/event-stream")
                        .header("Last-Event-ID", "#{lastSeq}")
                        .await(Duration.ofSeconds(30))
                        .on(ping(true)))
                // The shared heartbeat clock may send the first PING immediately.
                // Count alone cannot establish 60 seconds of live observation.
                .asLongAs(session -> !session.isFailed()
                        && (session.getInt("idlePingCount") < 4
                        || session.getLong("idleLastPingNano") - session.getLong("idleFirstPingNano") < MINIMUM_OBSERVED_NANOS))
                .on(exec(sse("idle-events").setCheck()
                        .await(Duration.ofSeconds(20)).on(ping(false))))
                .exec(sse("idle-events").close()))
                .exec(this::writeEvidence)
                .exitHereIfFailed();
        setUp(idle.injectOpen(rampSeconds == 0 ? atOnceUsers(users) : rampUsers(users).during(Duration.ofSeconds(rampSeconds))))
                .protocols(protocol)
                .assertions(global().failedRequests().count().is(0L));
    }

    private static SseMessageCheck ping(boolean first) {
        return sse.checkMessage(first ? "idle-first-ping" : "idle-next-ping")
                .matching(jsonPath("$.event").is("PING"))
                .check(jsonPath("$.event").is("PING"), jsonPath("$.data").exists())
                .postCheck(session -> {
                    if (session.isFailed()) return session;
                    long observedNano = System.nanoTime();
                    return first
                            ? session.set("idleFirstPingNano", observedNano).set("idleLastPingNano", observedNano).set("idlePingCount", 1)
                            : session.set("idleLastPingNano", observedNano).set("idlePingCount", session.getInt("idlePingCount") + 1);
                });
    }

    private Session writeEvidence(Session session) {
        int pingCount = session.contains("idlePingCount") ? session.getInt("idlePingCount") : 0;
        Long observedNanos = session.contains("idleFirstPingNano") && session.contains("idleLastPingNano")
                ? session.getLong("idleLastPingNano") - session.getLong("idleFirstPingNano") : null;
        boolean failed = session.isFailed() || pingCount < 4
                || observedNanos == null || observedNanos < MINIMUM_OBSERVED_NANOS;
        // UUIDs come from the validated feeder; topology is a fixed whitelist value.
        String line = "{\"runId\":\"" + session.getString("runId") + "\",\"vu\":" + session.userId()
                + ",\"topology\":\"" + topology + "\",\"rampSeconds\":" + rampSeconds + ",\"pingCount\":" + pingCount
                + ",\"observedMs\":" + (observedNanos == null ? "null" : Double.toString(observedNanos / 1_000_000.0))
                + ",\"failed\":" + failed + "}\n";
        synchronized (EVIDENCE_LOCK) {
            try {
                Files.writeString(evidenceFile, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot append idle SSE evidence", failure);
            }
        }
        return failed ? session.markAsFailed() : session;
    }

    private static Path evidenceFilePath() {
        String requested = System.getProperty("load.evidenceFile");
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("load.evidenceFile must name an absolute target/m4-3c NDJSON file");
        }
        Path file = Path.of(requested);
        Path reportRoot = repositoryRoot().resolve("target/m4-3c").toAbsolutePath().normalize();
        if (!file.isAbsolute() || !file.normalize().startsWith(reportRoot)
                || !file.getFileName().toString().endsWith(".ndjson")) {
            throw new IllegalArgumentException("load.evidenceFile must be an absolute NDJSON file under " + reportRoot);
        }
        file = file.normalize();
        try {
            // Validate the nearest existing path before creating any output parents.
            Path existing = file;
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            if (!existing.toRealPath().startsWith(reportRoot.toRealPath())) {
                throw new IllegalArgumentException("load.evidenceFile must remain within target/m4-3c");
            }
            Files.createDirectories(file.getParent());
            return file;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot prepare idle SSE evidence path", failure);
        }
    }

    private static String localBaseUrl() {
        String baseUrl = System.getProperty("load.baseUrl", "http://localhost:8082");
        if (!baseUrl.matches("http://(localhost|127\\.0\\.0\\.1):[0-9]+")) {
            throw new IllegalArgumentException("Only a local validation origin is allowed");
        }
        URI uri = URI.create(baseUrl);
        if (uri.getPort() < 1 || uri.getPort() > 65535) throw new IllegalArgumentException("Invalid validation port");
        return baseUrl;
    }

    private static List<Map<String, Object>> readRunsFile() {
        String fileProperty = System.getProperty("load.runsFile");
        if (fileProperty == null || fileProperty.isBlank()) {
            throw new IllegalArgumentException("load.runsFile must name an absolute target/m4-3c CSV file");
        }
        Path file = Path.of(fileProperty);
        Path reportRoot = repositoryRoot().resolve("target/m4-3c").toAbsolutePath().normalize();
        if (!file.isAbsolute() || !file.normalize().startsWith(reportRoot)
                || !file.getFileName().toString().endsWith(".csv")) {
            throw new IllegalArgumentException("load.runsFile must be an absolute CSV file under " + reportRoot);
        }
        try {
            if (!file.toRealPath().startsWith(reportRoot.toRealPath())) {
                throw new IllegalArgumentException("load.runsFile must remain within target/m4-3c");
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) throw new IllegalArgumentException("Empty runs CSV");
            String[] header = columns(lines.getFirst().replaceFirst("^\\uFEFF", ""));
            if (!header[0].equals("runId") || !header[1].equals("lastSeq")) {
                throw new IllegalArgumentException("Runs CSV header must be runId,lastSeq");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            Set<String> runIds = new HashSet<>();
            for (int index = 1; index < lines.size(); index++) {
                if (lines.get(index).isBlank()) continue;
                String[] values = columns(lines.get(index));
                String runId = values[0];
                if (!runId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || !UUID.fromString(runId).toString().equals(runId) || !runIds.add(runId)) {
                    throw new IllegalArgumentException("Runs CSV must contain distinct canonical UUIDs");
                }
                String lastSeq = values[1];
                if (!lastSeq.matches("[1-9][0-9]*") || Long.parseLong(lastSeq) < 1) {
                    throw new IllegalArgumentException("Runs CSV lastSeq must be a positive stream sequence");
                }
                rows.add(Map.of("runId", runId, "lastSeq", lastSeq));
            }
            if (rows.isEmpty()) throw new IllegalArgumentException("Runs CSV has no data rows");
            return List.copyOf(rows);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read runs CSV", failure);
        }
    }

    /** This restricted CSV accepts plain or quoted UUID/decimal cells, without event text. */
    private static String[] columns(String line) {
        String[] values = line.split(",", -1);
        if (values.length != 2) throw new IllegalArgumentException("Runs CSV must have exactly two columns");
        for (int index = 0; index < values.length; index++) {
            String value = values[index].trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.indexOf('"') >= 0) throw new IllegalArgumentException("Invalid runs CSV quoting");
            values[index] = value;
        }
        return values;
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
