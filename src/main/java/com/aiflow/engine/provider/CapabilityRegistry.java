package com.aiflow.engine.provider;

import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.config.AudioProperties;
import com.aiflow.engine.integration.HttpEndpointFactory;
import com.aiflow.engine.retrieval.EmbeddingProvider;
import com.aiflow.engine.retrieval.TieredEmbeddingProvider;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 能力x档位状态：给「现在到底能干什么」一个可读、可展示的答案。
 *
 * <p>要解决的问题：模型档位是动态的（本地服务没起、云端超时、密钥过期），而这些信息散在配置和异常栈里。
 * 用户看到一句「调用失败」时，真正该知道的是「哪一档挂了、现在用哪一档顶着、哪些功能因此不可用」。
 *
 * <p>档位矩阵（与 docs/architecture.md 一致，降级方向从上到下）：
 * <ul>
 *   <li>L0 全云端：能力最全（含多模态），但断网即不可用</li>
 *   <li>L1 本地全功能：本地模型 + 本地向量化，离线可用</li>
 *   <li>L2 本地模型 + 纯文本：本地对话可用，向量化不可用 => 知识库检索降级</li>
 *   <li>L3 预设话术 / 模拟档：没有可用的真实模型 —— mock 兜住流程时不假装成真实模型，节点输出里能看出 provider=mock</li>
 * </ul>
 *
 * <p>注意「降级」只统计**配置了却没起来**的档位：明确 enabled=false 的档位是选择，不是故障。
 */
@Component
public class CapabilityRegistry {

    private final ProviderRouter router;
    private final EmbeddingProvider embeddingProvider;
    private final HttpEndpointFactory endpoints;
    private final RetrievalProperties retrievalProperties;
    private final AudioProperties audioProperties;
    private final ObjectProvider<AsrProvider> asrProviders;
    private final ObjectProvider<TtsProvider> ttsProviders;

    public CapabilityRegistry(ProviderRouter router, EmbeddingProvider embeddingProvider,
                              HttpEndpointFactory endpoints, RetrievalProperties retrievalProperties,
                              AudioProperties audioProperties, ObjectProvider<AsrProvider> asrProviders,
                              ObjectProvider<TtsProvider> ttsProviders) {
        this.router = router;
        this.embeddingProvider = embeddingProvider;
        this.endpoints = endpoints;
        this.retrievalProperties = retrievalProperties;
        this.audioProperties = audioProperties;
        this.asrProviders = asrProviders;
        this.ttsProviders = ttsProviders;
    }

    public CapabilityReport report() {
        List<Candidate> chat = chatCandidates(Set.of(Capabilities.CHAT));
        List<Candidate> vision = chatCandidates(Set.of(Capabilities.CHAT, Capabilities.VISION));
        List<Candidate> embedding = embeddingCandidates();
        List<Candidate> cloudKb = cloudKnowledgeBaseCandidates();
        AudioChannel asr = audioChannel(Capabilities.ASR, audioProperties.asr().enabled(),
                audioProperties.asr().provider(), audioProperties.asr().model(), audioProperties.asr().apiKeyEnv(),
                probeAudio(audioProperties.asr().enabled(), audioProperties.asr().provider(), asrProviders,
                        AsrProvider::id, AsrProvider::available));
        AudioChannel tts = audioChannel(Capabilities.TTS, audioProperties.tts().enabled(),
                audioProperties.tts().provider(), audioProperties.tts().model(), audioProperties.tts().apiKeyEnv(),
                probeAudio(audioProperties.tts().enabled(), audioProperties.tts().provider(), ttsProviders,
                        TtsProvider::id, TtsProvider::available));

        Candidate activeChat = firstAvailable(chat);
        Candidate activeVision = firstAvailable(vision);
        boolean embeddingReal = embeddingProvider instanceof TieredEmbeddingProvider && embeddingProvider.healthy();
        boolean embeddingUp = firstAvailable(embedding) != null;
        String activeTier = activeChat == null ? "none" : activeChat.tier();

        String level;
        String levelName;
        if (("cloud".equals(activeTier) || "local".equals(activeTier)) && embeddingReal) {
            level = "cloud".equals(activeTier) ? "L0" : "L1";
            levelName = "cloud".equals(activeTier) ? "全云端（能力最全，依赖网络）" : "本地全功能（离线可用）";
        } else if ("cloud".equals(activeTier) || "local".equals(activeTier)) {
            level = "L2";
            levelName = "本地模型 + 纯文本（知识库检索降级）";
        } else {
            level = "L3";
            levelName = "预设话术 / 模拟档（没有可用的真实模型）";
        }

        List<String> notices = new ArrayList<>();
        if (activeChat == null) {
            notices.add("没有可用的对话档位：启动本地模型（Ollama）或配置云端密钥");
        } else {
            List<Candidate> broken = brokenHigherPriority(chat, activeChat);
            if (!broken.isEmpty()) {
                notices.add("已降级：配置的档位 " + joinIds(broken) + " 不可用，当前由 " + activeChat.id() + " 承接");
            }
        }
        if (activeVision == null) {
            notices.add("没有可用的多模态（vision）档位：图片问答节点会直接失败");
        } else if ("mock".equals(activeVision.id())) {
            notices.add("多模态当前由 mock 承接：只回图片摘要，不代表真实识图能力（配置云端 VL 或本地多模态模型后自动上移）");
        }
        if ("http".equalsIgnoreCase(retrievalProperties.embedding().mode()) && !embeddingUp) {
            notices.add("向量化档位全部不可用：知识库入库与检索会直接失败");
        }
        if ("mock".equalsIgnoreCase(retrievalProperties.embedding().mode())) {
            notices.add("向量化为 mock-embedding（离线演示档）：检索质量只用于联调，不代表真实模型表现");
        }
        addAudioNotice(notices, asr);
        addAudioNotice(notices, tts);

        return new CapabilityReport(level, levelName, notices.isEmpty() ? null : String.join("；", notices),
                List.of(new Channel("chat", channelStatus(chat, activeChat), activeChat == null ? null : activeChat.id(),
                        activeTier, chat),
                        new Channel("vision", channelStatus(vision, activeVision),
                                activeVision == null ? null : activeVision.id(),
                                activeVision == null ? "none" : activeVision.tier(), vision),
                        new Channel("embedding", channelStatus(embedding, firstAvailable(embedding)),
                                providerId(firstAvailable(embedding)), tierOf(firstAvailable(embedding)), embedding),
                        new Channel("cloud-knowledge-base", "ok",
                                firstAvailable(cloudKb) == null ? null : "cloud-kb", "cloud", cloudKb),
                        asr.channel(), tts.channel()));
    }

    private <T> AudioAvailability probeAudio(boolean channelEnabled, String configuredProvider,
                                             ObjectProvider<T> providers, Function<T, String> providerId,
                                             Predicate<T> available) {
        if (!audioProperties.enabled() || !channelEnabled || !audioProperties.mockMode()
                || !"mock".equalsIgnoreCase(configuredProvider)) {
            return new AudioAvailability(false, false);
        }
        try {
            T provider = providers.getIfAvailable();
            if (provider == null) {
                return new AudioAvailability(false, false);
            }
            return new AudioAvailability(true, "mock".equals(providerId.apply(provider)) && available.test(provider));
        } catch (RuntimeException ignored) {
            // Bean resolution and probe failures only affect this channel; external details stay private.
            return new AudioAvailability(true, false);
        }
    }

    private AudioChannel audioChannel(String capability, boolean channelEnabled, String providerId,
                                      String model, String apiKeyEnv, AudioAvailability availability) {
        boolean enabled = audioProperties.enabled() && channelEnabled;
        boolean mock = enabled && availability.available() && audioProperties.mockMode()
                && "mock".equalsIgnoreCase(providerId);
        String status = mock ? "mock" : "unavailable";
        String activeProvider = mock ? providerId : null;
        String activeTier = mock ? "mock" : "none";
        String detail;
        if (!audioProperties.enabled()) {
            detail = "未启用（aiflow.audio.enabled=false）";
        } else if (!channelEnabled) {
            detail = "通道已禁用（aiflow.audio." + capability + ".enabled=false）";
        } else if (audioProperties.disabledMode()) {
            detail = "音频模式已关闭（aiflow.audio.mode=disabled）";
        } else if (!audioProperties.mockMode()) {
            detail = "未配置可用的 " + capability + " provider（mode=" + audioProperties.mode()
                    + "，凭证变量名=" + apiKeyEnv + "）";
        } else if (!"mock".equalsIgnoreCase(providerId)) {
            detail = "mock 模式要求 provider=mock，当前为 " + providerId;
        } else if (!availability.present()) {
            detail = "未装配可用的 " + capability + " provider";
        } else if (!availability.available()) {
            detail = "已装配的 " + capability + " provider 当前不可用";
        } else {
            detail = "mock 音频能力（仅用于联调，不代表真实厂商能力；model=" + model + "）";
        }
        Candidate candidate = new Candidate(providerId, mock ? "mock" : "none", mock,
                enabled && !audioProperties.disabledMode(), model, detail, 0);
        return new AudioChannel(new Channel(capability, status, activeProvider, activeTier, List.of(candidate)));
    }

    private static void addAudioNotice(List<String> notices, AudioChannel audio) {
        if ("mock".equals(audio.channel().status())) {
            notices.add(audio.channel().channel() + " 当前由 mock 承接：仅用于联调，不代表真实厂商能力");
        } else {
            Candidate candidate = audio.channel().candidates().getFirst();
            notices.add(audio.channel().channel() + " 不可用：" + candidate.detail());
        }
    }

    private List<Candidate> chatCandidates(Set<String> requiredCapabilities) {
        Map<String, HttpEndpointFactory.EndpointView> views = new HashMap<>();
        for (HttpEndpointFactory.EndpointView view : endpoints.chatEndpoints()) {
            views.put(view.id(), view);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ChatProvider provider : router.all()) {
            if (!provider.capabilities().containsAll(requiredCapabilities)) {
                continue;
            }
            HttpEndpointFactory.EndpointView view = views.get(provider.id());
            if (view == null) {
                candidates.add(new Candidate(provider.id(), "mock".equals(provider.id()) ? "mock" : "local",
                        provider.healthy(), true, provider.id(), provider.description(),
                        router.priorityOf(provider.id())));
                continue;
            }
            candidates.add(new Candidate(view.id(), view.tier(), provider.healthy(),
                    view.enabled() && view.configured(), view.model(), describe(view),
                    router.priorityOf(view.id())));
        }
        candidates.sort(Comparator.comparingInt(Candidate::order));
        return candidates;
    }

    private List<Candidate> embeddingCandidates() {
        List<Candidate> candidates = new ArrayList<>();
        if (embeddingProvider instanceof TieredEmbeddingProvider tiered) {
            int index = 0;
            for (TieredEmbeddingProvider.Tier tier : tiered.tiers()) {
                EndpointHealth.Snapshot snapshot = tier.health().snapshot();
                candidates.add(new Candidate(tier.provider().id(), tier.kind(), tier.health().available(), true,
                        tier.name(), snapshot.state() + (snapshot.lastError() == null ? "" : "：" + snapshot.lastError()),
                        index++));
            }
            return candidates;
        }
        candidates.add(new Candidate(embeddingProvider.id(), "mock", embeddingProvider.healthy(), true,
                retrievalProperties.embedding().model(), "离线演示档（确定性哈希），不产生可评估的检索质量", 0));
        return candidates;
    }

    private List<Candidate> cloudKnowledgeBaseCandidates() {
        RetrievalProperties.Cloud cloud = retrievalProperties.cloud();
        return List.of(new Candidate("cloud-kb", "cloud", cloud.enabled(), cloud.enabled(), cloud.source(),
                cloud.enabled() ? "已启用（mode=" + cloud.mode() + "）" : "未启用（aiflow.retrieval.cloud.enabled=false）", 0));
    }

    private static String describe(HttpEndpointFactory.EndpointView view) {
        if (!view.enabled()) {
            return "已禁用（enabled=false）";
        }
        if (!view.configured()) {
            return "未配置 base-url（云端地址与密钥只从环境变量注入）";
        }
        EndpointHealth.Snapshot snapshot = view.health().snapshot();
        return snapshot.state() + (snapshot.lastError() == null ? "" : "：" + snapshot.lastError());
    }

    private static List<Candidate> brokenHigherPriority(List<Candidate> candidates, Candidate active) {
        return candidates.stream()
                .filter(candidate -> candidate.order() < active.order() && candidate.expected() && !candidate.available())
                .toList();
    }

    private static String channelStatus(List<Candidate> candidates, Candidate active) {
        if (active == null) {
            return "unavailable";
        }
        return brokenHigherPriority(candidates, active).isEmpty() ? "ok" : "degraded";
    }

    private static Candidate firstAvailable(List<Candidate> candidates) {
        return candidates.stream().filter(Candidate::available).findFirst().orElse(null);
    }

    private static String providerId(Candidate candidate) {
        return candidate == null ? null : candidate.id();
    }

    private static String tierOf(Candidate candidate) {
        return candidate == null ? "none" : candidate.tier();
    }

    private static String joinIds(List<Candidate> candidates) {
        return String.join("/", candidates.stream().map(Candidate::id).toList());
    }

    public record CapabilityReport(String level, String levelName, String notice, List<Channel> channels) {
    }

    public record Channel(String channel, String status, String activeProvider, String activeTier,
                          List<Candidate> candidates) {
    }

    /** expected 表示「配置上本来应该有这一档」：用来把「配置关闭」与「配置了但挂了」区分开。 */
    public record Candidate(String id, String tier, boolean available, boolean expected, String model, String detail,
                            int order) {
    }

    private record AudioChannel(Channel channel) {
    }

    private record AudioAvailability(boolean present, boolean available) {
    }
}
