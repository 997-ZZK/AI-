package com.aiflow.engine.profile;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure aggregation of recorded facts; it never treats missing usage as zero. */
public record ProfileStatistics(long runCount, Map<String, Long> statuses, List<WorkflowCount> workflows,
                                List<Long> hours, List<ToolCount> tools, TokenCount tokens) {

    public record RunSample(UUID runId, UUID workflowId, String name, int version, String status, Instant at) {
    }

    /** Null tokens means absent, malformed or negative tokensUsed in the recorded output. */
    public record NodeSample(String nodeType, Instant at, Long tokens, String provider) {
    }

    public record ToolSample(String toolId, String kind, String outcome, long count) {
    }

    public record WorkflowCount(UUID workflowId, String name, int version, long count) {
    }

    public record ToolCount(String toolId, String kind, long success, long replayed, long failed, long denied,
                            long unknown) {
    }

    public record TokenCount(long mockTokens, long realTokens, long unknownProviderTokens, long unknownNodes,
                             long recordedNodes) {
    }

    public static ProfileStatistics empty() {
        return aggregate(List.of(), List.of(), List.of(), Instant.EPOCH, Instant.EPOCH, ZoneId.of("UTC"));
    }

    public static ProfileStatistics aggregate(List<RunSample> runs, List<NodeSample> nodes, List<ToolSample> audits,
                                               Instant from, Instant to, ZoneId zone) {
        Map<String, Long> statuses = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "RUNNING", "WAITING_APPROVAL", "SUCCEEDED", "DEGRADED", "FAILED",
                "CANCELED")) {
            statuses.put(status, 0L);
        }
        Map<WorkflowKey, Long> workflows = new LinkedHashMap<>();
        List<Long> hours = new ArrayList<>(Collections.nCopies(24, 0L));
        HashSet<UUID> seen = new HashSet<>();
        for (RunSample run : runs) {
            if (!inside(run.at(), from, to) || !seen.add(run.runId())) {
                continue;
            }
            statuses.merge(run.status() == null ? "UNKNOWN" : run.status(), 1L, Long::sum);
            workflows.merge(new WorkflowKey(run.workflowId(), run.name(), run.version()), 1L, Long::sum);
            int hour = run.at().atZone(zone).getHour();
            hours.set(hour, hours.get(hour) + 1);
        }
        List<WorkflowCount> workflowCounts = workflows.entrySet().stream()
                .map(entry -> new WorkflowCount(entry.getKey().id(), entry.getKey().name(), entry.getKey().version(),
                        entry.getValue()))
                .sorted(Comparator.comparingLong(WorkflowCount::count).reversed()
                        .thenComparing(value -> value.workflowId().toString()).thenComparingInt(WorkflowCount::version))
                .toList();

        Map<ToolKey, long[]> toolCounts = new LinkedHashMap<>();
        for (ToolSample audit : audits) {
            long[] counts = toolCounts.computeIfAbsent(new ToolKey(audit.toolId(), audit.kind()), key -> new long[5]);
            int index = switch (audit.outcome() == null ? "" : audit.outcome()) {
                case "SUCCESS" -> 0;
                case "REPLAYED" -> 1;
                case "FAILED" -> 2;
                case "DENIED" -> 3;
                default -> 4;
            };
            counts[index] += audit.count();
        }
        List<ToolCount> tools = toolCounts.entrySet().stream()
                .map(entry -> new ToolCount(entry.getKey().id(), entry.getKey().kind(), entry.getValue()[0],
                        entry.getValue()[1], entry.getValue()[2], entry.getValue()[3], entry.getValue()[4]))
                .sorted(Comparator.comparing(ToolCount::toolId).thenComparing(ToolCount::kind))
                .toList();

        long mock = 0, real = 0, unknownProvider = 0, unknown = 0, recorded = 0;
        for (NodeSample node : nodes) {
            if (!("llm.chat".equals(node.nodeType()) || "llm.vision".equals(node.nodeType()))
                    || !inside(node.at(), from, to)) {
                continue;
            }
            if (node.tokens() == null || node.tokens() < 0) {
                unknown++;
                continue;
            }
            recorded++;
            if (node.provider() == null || node.provider().isBlank()) {
                unknownProvider += node.tokens();
            } else if ("mock".equals(node.provider())) {
                mock += node.tokens();
            } else {
                real += node.tokens();
            }
        }
        return new ProfileStatistics(seen.size(), Collections.unmodifiableMap(statuses), workflowCounts,
                List.copyOf(hours), tools, new TokenCount(mock, real, unknownProvider, unknown, recorded));
    }

    private static boolean inside(Instant at, Instant from, Instant to) {
        return at != null && !at.isBefore(from) && !at.isAfter(to);
    }

    private record WorkflowKey(UUID id, String name, int version) {
    }

    private record ToolKey(String id, String kind) {
    }
}
