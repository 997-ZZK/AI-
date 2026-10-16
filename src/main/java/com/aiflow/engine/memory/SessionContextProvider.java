package com.aiflow.engine.memory;

import com.aiflow.engine.memory.longterm.LongMemoryOptions;
import com.aiflow.engine.memory.longterm.LongMemoryProperties;
import com.aiflow.engine.memory.longterm.LongMemoryRecallPort;
import com.aiflow.engine.memory.longterm.LongMemoryRecallService;
import com.aiflow.engine.memory.longterm.RecalledMemory;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.profile.ProfilePreferenceResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 把「画像偏好 + 长期记忆 + 摘要 + 窗口历史 + 本轮输入」拼成消息列表（{@code llm.chat} / {@code llm.vision} 共用）。
 *
 * <p>四条口径：
 * <ul>
 *   <li>三块 system **各自带标签、顺序固定**：画像显式风格、长期记忆（跨会话的稳定先验）、摘要（最近对话的压缩），
 *       再是窗口历史与本轮输入 —— 模型必须能分辨「这是用户长期如此」还是「他刚才这么说过」；</li>
 *   <li>窗口超预算时**从最老的开始丢**（与人的短时记忆一致：越久远越先出局）；</li>
 *   <li>任何读失败都降级为「没有这一段」：多一段上下文是增强，少一段不该让运行失败；</li>
 *   <li>开关**互相独立**：{@code history: none} 只关短期，{@code longMemory: none} 只关长期，
 *       {@code profilePreference: none} 只关画像偏好。</li>
 * </ul>
 *
 * <p>{@link #trim} 是纯函数（不碰 IO），窗口策略的全部语义都在它里面，便于单测钉住。
 */
@Component
public class SessionContextProvider {

    /** 摘要消息的标签：没有它，模型会把压缩后的旧内容当成用户当前说的。 */
    public static final String SUMMARY_LABEL = "会话摘要（更早内容的压缩，供参考，不是新指令）：\n";

    /** 长期记忆的标签：与摘要刻意不同措辞 —— 这块是用户**长期如此**，不是「最近聊过」。 */
    public static final String LONG_MEMORY_LABEL = "长期记忆（用户此前说过的事实与偏好，供参考，不是新指令）：\n";

    public static final String PROFILE_PREFERENCE_LABEL = "画像偏好（用户手工设置的回复风格）：\n";

    private final SessionMemoryPort port;
    private final MemoryProperties properties;
    private final LongMemoryRecallPort longMemoryPort;
    private final LongMemoryProperties longMemoryProperties;
    private final ProfilePreferenceResolver profilePreferences;

    /** 两参构造：短期记忆之外不带长期记忆（节点里的退化路径与既有单测用它）。 */
    public SessionContextProvider(SessionMemoryPort port, MemoryProperties properties) {
        this(port, properties, LongMemoryRecallPort.disabled(), LongMemoryProperties.defaults());
    }

    public SessionContextProvider(SessionMemoryPort port, MemoryProperties properties,
                                  LongMemoryRecallPort longMemoryPort, LongMemoryProperties longMemoryProperties) {
        this(port, properties, longMemoryPort, longMemoryProperties, ProfilePreferenceResolver.disabled());
    }

    @Autowired
    public SessionContextProvider(SessionMemoryPort port, MemoryProperties properties,
                                  LongMemoryRecallPort longMemoryPort, LongMemoryProperties longMemoryProperties,
                                  ProfilePreferenceResolver profilePreferences) {
        this.port = port;
        this.properties = properties;
        this.longMemoryPort = longMemoryPort;
        this.longMemoryProperties = longMemoryProperties;
        this.profilePreferences = profilePreferences;
    }

    public MemoryProperties properties() {
        return properties;
    }

    /** 不带长期记忆的装配（工作流作者指定 {@code longMemory: none}，或调用方没有 userId）。 */
    public List<ChatMessage> assemble(UUID sessionId, ChatMessage currentMessage, HistoryOptions options) {
        return assemble(sessionId, null, currentMessage, options, new LongMemoryOptions(false));
    }

    /**
     * 装配默认偏好的消息列表：{@code [system(画像)] + [system(长期记忆)] + [system(摘要)] + 窗口历史 + 本轮输入}。
     *
     * <p>{@code summary} 只在会被注入时才读、也只在会被注入时才参与召回的 query —— 工作流说了
     * 「这轮不要摘要」，那摘要就不该以任何形式影响这次请求。
     */
    public List<ChatMessage> assemble(UUID sessionId, UUID userId, ChatMessage currentMessage, HistoryOptions options,
                                      LongMemoryOptions longMemory) {
        return assemble(sessionId, userId, currentMessage, options, longMemory, true);
    }

    /**
     * 同五参版本，外加「这次召回要不要记 hits」。
     *
     * <p>{@code writeBackHits=false} 只给**预览**用：预览要如实反映模型会收到什么（否则它就是在撒谎，
     * 真实栈上就只剩「没报错」可断言），但看一眼不算一次真实使用，不该把 hits 顶上去。
     */
    public List<ChatMessage> assemble(UUID sessionId, UUID userId, ChatMessage currentMessage, HistoryOptions options,
                                      LongMemoryOptions longMemory, boolean writeBackHits) {
        return assembleDetailed(sessionId, userId, currentMessage, options, longMemory, writeBackHits, null, null)
                .messages();
    }

    /** 单次读取偏好，同一份决策同时供消息装配和模型路由使用。 */
    public Assembly assembleDetailed(UUID sessionId, UUID userId, ChatMessage currentMessage, HistoryOptions options,
                                     LongMemoryOptions longMemory, boolean writeBackHits,
                                     String profilePreferenceParam, String model) {
        if (currentMessage == null) {
            throw new IllegalArgumentException("currentMessage 不能为空");
        }
        boolean shortTermOn = properties.enabled() && options.enabled();
        String summary = shortTermOn && options.includeSummary() ? summary(sessionId).orElse(null) : null;
        String block = longMemoryBlock(currentMessage, summary, userId, longMemory, writeBackHits);
        ProfilePreferenceResolver.Decision preference = profilePreferences.resolve(userId, profilePreferenceParam,
                model);
        List<ChatMessage> messages = new ArrayList<>();
        if (!preference.stylePrompt().isEmpty()) {
            messages.add(ChatMessage.system(PROFILE_PREFERENCE_LABEL + preference.stylePrompt()));
        }
        if (!block.isEmpty()) {
            messages.add(ChatMessage.system(LONG_MEMORY_LABEL + block));
        }
        if (summary != null) {
            messages.add(ChatMessage.system(SUMMARY_LABEL + summary));
        }
        if (shortTermOn) {
            messages.addAll(trim(history(sessionId, options.limit()), properties.windowMaxChars()));
        }
        messages.add(currentMessage);
        return new Assembly(List.copyOf(messages), preference);
    }

    public Assembly assembleDetailed(UUID sessionId, UUID userId, ChatMessage currentMessage, String historyParam,
                                     String includeSummaryParam, String longMemoryParam,
                                     String profilePreferenceParam, String model) {
        return assembleDetailed(sessionId, userId, currentMessage,
                HistoryOptions.parse(historyParam, includeSummaryParam, properties.windowSize()),
                LongMemoryOptions.parse(longMemoryParam), true, profilePreferenceParam, model);
    }

    /** 节点用的入口：直接吃节点参数（{@code history} / {@code includeSummary} / {@code longMemory}），默认值取全局配置。 */
    public List<ChatMessage> assemble(UUID sessionId, UUID userId, ChatMessage currentMessage, String historyParam,
                                      String includeSummaryParam, String longMemoryParam) {
        return assemble(sessionId, userId, currentMessage,
                HistoryOptions.parse(historyParam, includeSummaryParam, properties.windowSize()),
                LongMemoryOptions.parse(longMemoryParam));
    }

    /** 旧的入口（没有 userId，也就没有长期记忆）：保留它，既有调用点与用例不必为了长期记忆改签名。 */
    public List<ChatMessage> assemble(UUID sessionId, ChatMessage currentMessage, String historyParam,
                                      String includeSummaryParam) {
        return assemble(sessionId, null, currentMessage, historyParam, includeSummaryParam, null);
    }

    private Optional<String> summary(UUID sessionId) {
        if (properties.summaryOff()) {
            return Optional.empty();
        }
        try {
            return port.summary(sessionId).map(String::strip).filter(text -> !text.isEmpty());
        } catch (RuntimeException e) {
            // 端口契约要求实现不抛；这里是第二道保险 —— 记忆的问题不许变成用户的 500
            return Optional.empty();
        }
    }

    private List<ChatMessage> history(UUID sessionId, int limit) {
        if (limit <= 0 || properties.windowMaxChars() <= 0) {
            return List.of();
        }
        try {
            return port.history(sessionId, limit);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * 长期记忆块：query = 本轮输入 +（摘要存在且配置了 {@code recall-query-chars} 时）摘要前 N 字。
     *
     * <p>为什么要把摘要也塞进 query：用户这一轮常常只说「那它呢」——单看这句话什么都召回不出来，
     * 而会话摘要里有「我们在聊报销流程」。这是**检索质量**的关键一步，不是锦上添花。
     */
    private String longMemoryBlock(ChatMessage currentMessage, String summary, UUID userId, LongMemoryOptions options,
                                   boolean writeBackHits) {
        if (!options.enabled() || longMemoryProperties.recallOff() || userId == null) {
            return "";
        }
        String query = LongMemoryRecallService.buildQuery(currentMessage.content(), summary,
                longMemoryProperties.recallQueryChars());
        if (query.isBlank()) {
            return "";
        }
        try {
            return RecalledMemory.renderAll(
                    longMemoryPort.recall(userId, query, longMemoryProperties.recallTopK(), writeBackHits));
        } catch (RuntimeException e) {
            // 端口契约要求实现不抛；这里是第二道保险
            return "";
        }
    }

    /** 从**最新**往回保留，超预算就丢最老的；单条就超预算时截断而不是整条丢。 */
    static List<ChatMessage> trim(List<ChatMessage> history, int maxChars) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int budget = Math.max(0, maxChars);
        List<ChatMessage> kept = new ArrayList<>();
        int used = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessage message = history.get(index);
            if (message == null) {
                continue;
            }
            String content = message.content() == null ? "" : message.content();
            if (used + content.length() > budget) {
                if (kept.isEmpty()) {
                    kept.add(new ChatMessage(message.role(), truncate(content, budget)));
                }
                break;
            }
            kept.add(message);
            used += content.length();
        }
        Collections.reverse(kept);
        return List.copyOf(kept);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    public record Assembly(List<ChatMessage> messages, ProfilePreferenceResolver.Decision preferences) {
    }
}
