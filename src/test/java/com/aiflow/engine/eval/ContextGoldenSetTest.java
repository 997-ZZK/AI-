package com.aiflow.engine.eval;

import com.aiflow.engine.api.service.SessionMemoryService;
import com.aiflow.engine.budget.InMemoryTokenUsageStore;
import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.config.BudgetProperties;
import com.aiflow.engine.config.EngineProperties;
import com.aiflow.engine.config.ProviderProperties;
import com.aiflow.engine.config.SafetyProperties;
import com.aiflow.engine.event.InMemoryRunEventBus;
import com.aiflow.engine.graph.NodeDef;
import com.aiflow.engine.memory.MemoryMaintenanceExecutor;
import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.SessionContextProvider;
import com.aiflow.engine.memory.SessionMemoryPort;
import com.aiflow.engine.memory.longterm.LongMemoryProperties;
import com.aiflow.engine.memory.longterm.MemoryKind;
import com.aiflow.engine.memory.longterm.MemorySource;
import com.aiflow.engine.memory.longterm.RecalledMemory;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.builtin.LlmNodeExecutor;
import com.aiflow.engine.persistence.entity.SessionEntity;
import com.aiflow.engine.persistence.repo.MessageRepository;
import com.aiflow.engine.persistence.repo.SessionMemoryRepository;
import com.aiflow.engine.persistence.repo.SessionRepository;
import com.aiflow.engine.profile.ProfilePreferencePort;
import com.aiflow.engine.profile.ProfilePreferenceResolver;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatProvider;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ChatResponse;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.safety.PermissiveSafety;
import com.aiflow.engine.safety.StreamingSafetyGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.aiflow.engine.eval.OfflineGoldenSet.requireBoolean;
import static com.aiflow.engine.eval.OfflineGoldenSet.requireFields;
import static com.aiflow.engine.eval.OfflineGoldenSet.requireInt;
import static com.aiflow.engine.eval.OfflineGoldenSet.requireText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Data-driven contracts for the real assembly and chat node; fixed replies are not quality scores. */
class ContextGoldenSetTest {

    private static final Set<String> CATEGORIES = Set.of("assembly", "switches", "read-failure", "role-boundary",
            "profile-policy", "preview");
    private static final OfflineGoldenSet GOLDEN = OfflineGoldenSet.load("/eval/layer2-context-golden.json",
            "context", 12, CATEGORIES);
    private static final UUID USER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SESSION = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID RUN = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final String REPLY = "合成固定回答";
    private static final Set<String> INPUT_FIELDS = Set.of("utterance", "history", "summary", "memories",
            "profileEnabled", "preferences", "params", "failures", "memoryEnabled", "windowMaxChars", "budgetUsed");
    private static final Set<String> EXPECTED_FIELDS = Set.of("status", "provider", "model", "messages", "reads",
            "recallQuery", "writeBackHits", "preview");

    @TestFactory
    Stream<DynamicTest> contextContracts() {
        return GOLDEN.cases().stream().map(testCase -> DynamicTest.dynamicTest(testCase.id(),
                () -> GOLDEN.verify(testCase, () -> verifyCase(testCase))));
    }

    @AfterAll
    static void report() throws IOException {
        GOLDEN.writeReport();
    }

    private static void verifyCase(OfflineGoldenSet.Case testCase) {
        requireFields(testCase.input(), INPUT_FIELDS);
        requireFields(testCase.expected(), EXPECTED_FIELDS);
        Fixture fixture = new Fixture(testCase.input());
        boolean preview = switch (testCase.scenario()) {
            case "chat" -> false;
            case "default-preview" -> true;
            default -> throw new IllegalArgumentException("Unknown context scenario");
        };
        SessionMemoryService.MemoryPreview memoryPreview = null;
        if (preview) {
            // This endpoint represents default inheritance, not arbitrary prompt templates or node overrides.
            assertThat(fixture.params).isEmpty();
            assertThat(requireText(testCase.expected(), "provider")).isNotEqualTo("none");
            requireObject(testCase.expected(), "preview");
            memoryPreview = fixture.previewService().preview(SESSION, fixture.utterance);
        }
        NodeResult result = fixture.executor.execute(fixture.context());
        JsonNode expected = testCase.expected();
        assertThat(result.status().name()).isEqualTo(requireText(expected, "status"));
        String expectedProvider = requireText(expected, "provider");
        if ("none".equals(expectedProvider)) {
            assertThat(fixture.calls).isEmpty();
            assertThat(requireArray(expected, "messages")).isEmpty();
            assertThat(requireText(expected, "model")).isEqualTo("none");
            assertThat(fixture.budget.snapshot(USER).used()).isEqualTo(fixture.budgetUsed);
        } else {
            assertThat(fixture.calls).hasSize(1);
            assertThat(result.output()).containsEntry("provider", expectedProvider).containsEntry("reply", REPLY);
            assertThat(fixture.providersCalled).containsExactly(expectedProvider);
            ChatRequest request = fixture.calls.getFirst();
            String expectedModel = requireText(expected, "model");
            assertThat(request.model()).isEqualTo("none".equals(expectedModel) ? null : expectedModel);
            assertMessages(request.messages(), requireArray(expected, "messages"));
            assertThat(fixture.budget.snapshot(USER).used()).isEqualTo(fixture.budgetUsed + 4L);
            if (preview) {
                assertPreview(memoryPreview, request.messages(), requireObject(expected, "preview"));
            }
        }
        if (!preview) {
            assertThat(requireObject(expected, "preview").isEmpty()).isTrue();
        }
        JsonNode reads = requireObject(expected, "reads");
        requireFields(reads, Set.of("history", "summary", "longMemory", "profile"));
        assertThat(fixture.historyReads).isEqualTo(requireInt(reads, "history"));
        assertThat(fixture.summaryReads).isEqualTo(requireInt(reads, "summary"));
        assertThat(fixture.longMemoryReads).isEqualTo(requireInt(reads, "longMemory"));
        assertThat(fixture.profileReads).isEqualTo(requireInt(reads, "profile"));
        String expectedQuery = requireText(expected, "recallQuery");
        if (fixture.longMemoryReads == 0) {
            assertThat(fixture.queries).isEmpty();
            assertThat(expectedQuery).isEqualTo("none");
        } else {
            assertThat(fixture.queries).hasSize(fixture.longMemoryReads).allMatch(expectedQuery::equals);
        }
        assertThat(fixture.writeBackHits).containsExactlyElementsOf(booleans(requireArray(expected, "writeBackHits")));
    }

    private static void assertMessages(List<ChatMessage> messages, JsonNode expected) {
        assertThat(messages).hasSize(expected.size());
        for (int index = 0; index < messages.size(); index++) {
            JsonNode message = expected.get(index);
            requireFields(message, Set.of("role", "content"));
            assertThat(messages.get(index).role()).isEqualTo(requireText(message, "role"));
            assertThat(messages.get(index).content()).isEqualTo(requireText(message, "content"));
            assertThat(messages.get(index).images()).isEmpty();
        }
    }

    private static void assertPreview(SessionMemoryService.MemoryPreview preview, List<ChatMessage> actual,
                                      JsonNode expected) {
        requireFields(expected, Set.of("historyCount", "summaryIncluded", "longMemoryIncluded", "profileIncluded"));
        assertThat(preview).isNotNull();
        assertThat(preview.historyCount()).isEqualTo(requireInt(expected, "historyCount"));
        assertThat(preview.summaryIncluded()).isEqualTo(requireBoolean(expected, "summaryIncluded"));
        assertThat(preview.longMemoryIncluded()).isEqualTo(requireBoolean(expected, "longMemoryIncluded"));
        assertThat(preview.profileIncluded()).isEqualTo(requireBoolean(expected, "profileIncluded"));
        assertThat(preview.messages()).hasSize(actual.size());
        for (int index = 0; index < actual.size(); index++) {
            ChatMessage message = actual.get(index);
            SessionMemoryService.PreviewMessage viewed = preview.messages().get(index);
            assertThat(viewed.role()).isEqualTo(message.role());
            assertThat(viewed.chars()).isEqualTo(message.content().length());
            assertThat(viewed.truncated()).isEqualTo(message.content().length() > 500);
            assertThat(viewed.content()).isEqualTo(message.content().substring(0, Math.min(500, message.content().length())));
        }
    }

    private static JsonNode requireArray(JsonNode node, String field) {
        JsonNode result = node.get(field);
        if (result == null || !result.isArray()) {
            throw new IllegalArgumentException("Expected context array");
        }
        return result;
    }

    private static JsonNode requireObject(JsonNode node, String field) {
        JsonNode result = node.get(field);
        if (result == null || !result.isObject()) {
            throw new IllegalArgumentException("Expected context object");
        }
        return result;
    }

    private static List<Boolean> booleans(JsonNode node) {
        List<Boolean> result = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isBoolean()) {
                throw new IllegalArgumentException("Expected context boolean");
            }
            result.add(value.booleanValue());
        });
        return List.copyOf(result);
    }

    private static final class Fixture {

        private final String utterance;
        private final List<ChatMessage> history = new ArrayList<>();
        private final List<RecalledMemory> memories = new ArrayList<>();
        private final List<ProfilePreferencePort.Entry> preferences = new ArrayList<>();
        private final Map<String, Object> params = new LinkedHashMap<>();
        private final List<String> failures = new ArrayList<>();
        private final List<ChatRequest> calls = new ArrayList<>();
        private final List<String> providersCalled = new ArrayList<>();
        private final List<String> queries = new ArrayList<>();
        private final List<Boolean> writeBackHits = new ArrayList<>();
        private final SessionMemoryPort memoryPort;
        private final MemoryProperties memoryProperties;
        private final SessionContextProvider assembly;
        private final ProviderRouter router;
        private final TokenBudgetService budget;
        private final LlmNodeExecutor executor;
        private final int budgetUsed;
        private int historyReads;
        private int summaryReads;
        private int longMemoryReads;
        private int profileReads;

        private Fixture(JsonNode input) {
            utterance = requireText(input, "utterance");
            String summary = requireText(input, "summary");
            boolean profileEnabled = requireBoolean(input, "profileEnabled");
            for (JsonNode message : requireArray(input, "history")) {
                requireFields(message, Set.of("role", "content"));
                String role = requireText(message, "role");
                if (!Set.of("user", "assistant").contains(role)) {
                    throw new IllegalArgumentException("Unsupported synthetic history role");
                }
                history.add(new ChatMessage(role, requireText(message, "content")));
            }
            for (JsonNode memory : requireArray(input, "memories")) {
                requireFields(memory, Set.of("kind", "key", "value"));
                memories.add(new RecalledMemory(id("memory-" + memories.size()),
                        MemoryKind.valueOf(requireText(memory, "kind")), requireText(memory, "key"),
                        requireText(memory, "value"), 0.9));
            }
            for (JsonNode preference : requireArray(input, "preferences")) {
                requireFields(preference, Set.of("kind", "source", "key", "value"));
                preferences.add(new ProfilePreferencePort.Entry(id("preference-" + preferences.size()),
                        MemoryKind.valueOf(requireText(preference, "kind")),
                        MemorySource.valueOf(requireText(preference, "source")), requireText(preference, "key"),
                        requireText(preference, "value")));
            }
            JsonNode parameters = requireObject(input, "params");
            parameters.fields().forEachRemaining(entry -> {
                if (!Set.of("history", "includeSummary", "longMemory", "profilePreference", "model")
                        .contains(entry.getKey()) || !entry.getValue().isTextual()) {
                    throw new IllegalArgumentException("Expected synthetic parameter text");
                }
                params.put(entry.getKey(), entry.getValue().textValue());
            });
            for (JsonNode failure : requireArray(input, "failures")) {
                if (!failure.isTextual() || !Set.of("history", "summary", "longMemory", "profile")
                        .contains(failure.textValue()) || failures.contains(failure.textValue())) {
                    throw new IllegalArgumentException("Invalid synthetic read failure");
                }
                failures.add(failure.textValue());
            }
            memoryProperties = new MemoryProperties(requireBoolean(input, "memoryEnabled"), 8,
                    requireInt(input, "windowMaxChars"), true, 6, 8000, 1200, true, 5);
            memoryPort = new SessionMemoryPort() {
                @Override
                public List<ChatMessage> history(UUID sessionId, int limit) {
                    assertThat(sessionId).isEqualTo(SESSION);
                    historyReads++;
                    failIfRequested("history");
                    return List.copyOf(history.subList(Math.max(0, history.size() - limit), history.size()));
                }

                @Override
                public Optional<String> summary(UUID sessionId) {
                    assertThat(sessionId).isEqualTo(SESSION);
                    summaryReads++;
                    failIfRequested("summary");
                    return summary.isEmpty() ? Optional.empty() : Optional.of(summary);
                }
            };
            ProfilePreferenceResolver profile = new ProfilePreferenceResolver(user -> {
                assertThat(user).isEqualTo(USER);
                profileReads++;
                failIfRequested("profile");
                return new ProfilePreferencePort.Snapshot(profileEnabled, preferences);
            });
            assembly = new SessionContextProvider(memoryPort, memoryProperties, (user, query, topK, writeBack) -> {
                assertThat(user).isEqualTo(USER);
                assertThat(topK).isEqualTo(5);
                longMemoryReads++;
                queries.add(query);
                writeBackHits.add(writeBack);
                failIfRequested("longMemory");
                return List.copyOf(memories);
            }, LongMemoryProperties.defaults(), profile);
            ProviderProperties defaults = ProviderProperties.defaults();
            ProviderProperties properties = new ProviderProperties("auto", new String[]{"cloud", "local", "mock"},
                    List.of("local", "mock"), defaults.mock(), defaults.endpoints(), defaults.probe(), defaults.breaker());
            router = new ProviderRouter(List.of(new Capturing("cloud"), new Capturing("local")), properties);
            BudgetProperties limits = new BudgetProperties(true, 1000, 0.8, "UTC", "合成额度用尽", "memory");
            budget = new TokenBudgetService(new InMemoryTokenUsageStore(), limits);
            budgetUsed = requireInt(input, "budgetUsed");
            if (budgetUsed < 0) {
                throw new IllegalArgumentException("Negative synthetic budget");
            }
            budget.charge(USER, budgetUsed);
            executor = new LlmNodeExecutor(router, PermissiveSafety.create(), SafetyProperties.defaults(), budget,
                    limits, new StreamingSafetyGuard(PermissiveSafety.create(), SafetyProperties.defaults()), assembly);
        }

        private NodeContext context() {
            Map<String, Object> nodeParams = new HashMap<>(params);
            nodeParams.put("prompt", "${input}");
            NodeDef node = new NodeDef("chat", "llm.chat", "合成上下文验证", nodeParams, Set.of(Capabilities.CHAT),
                    null, null);
            return new NodeContext(RUN, SESSION, USER, node, new HashMap<>(Map.of("input", utterance)),
                    new InMemoryRunEventBus(EngineProperties.defaults()), () -> false);
        }

        private SessionMemoryService previewService() {
            SessionRepository sessions = mock(SessionRepository.class);
            when(sessions.findById(SESSION)).thenReturn(Optional.of(new SessionEntity(SESSION, USER,
                    "合成预览会话", Instant.EPOCH)));
            return new SessionMemoryService(mock(SessionMemoryRepository.class), mock(MessageRepository.class),
                    sessions, router, budget, assembly, memoryPort, mock(MemoryMaintenanceExecutor.class),
                    memoryProperties, new InMemoryRunEventBus(EngineProperties.defaults()));
        }

        private void failIfRequested(String port) {
            if (failures.contains(port)) {
                throw new IllegalStateException("synthetic-read-failure");
            }
        }

        private final class Capturing implements ChatProvider {

            private final String name;

            private Capturing(String name) {
                this.name = name;
            }

            @Override public String id() { return name; }
            @Override public Set<String> capabilities() { return Set.of(Capabilities.CHAT); }

            @Override
            public ChatResponse chat(ChatRequest request, Consumer<String> sink) {
                calls.add(request);
                providersCalled.add(name);
                sink.accept(REPLY);
                return new ChatResponse(REPLY, name, 2, 2);
            }
        }
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
