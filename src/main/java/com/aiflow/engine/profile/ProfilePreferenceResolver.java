package com.aiflow.engine.profile;

import com.aiflow.engine.memory.longterm.MemoryKeys;
import com.aiflow.engine.memory.longterm.MemoryKind;
import com.aiflow.engine.memory.longterm.MemorySource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 显式偏好的白名单规则。注入固定说明，库中任意正文不作为指令拼接。 */
@Component
public class ProfilePreferenceResolver {

    public static final String RESPONSE_STYLE_KEY = "response-style";
    public static final String MODEL_POLICY_KEY = "model-policy";
    private static final Set<String> STYLES = Set.of("concise", "balanced", "detailed");
    private static final Set<String> POLICIES = Set.of("default", "cheap-first");

    private final ProfilePreferencePort port;

    public ProfilePreferenceResolver(ProfilePreferencePort port) {
        this.port = port;
    }

    public static ProfilePreferenceResolver disabled() {
        return new ProfilePreferenceResolver(ProfilePreferencePort.disabled());
    }

    /** 真实装配与预览共用。读取失败仅关闭画像应用，不传播异常正文。 */
    public Decision resolve(UUID userId, String profilePreference, String model) {
        String option = normalizedValue(profilePreference);
        boolean workflowAllowed = option.isEmpty() || "inherit".equals(option);
        if (userId == null) {
            return empty(false, workflowAllowed, "no-user");
        }
        ProfilePreferencePort.Snapshot snapshot;
        try {
            snapshot = port.load(userId);
            if (snapshot == null) {
                return empty(false, workflowAllowed, "profile-read-failed");
            }
        } catch (RuntimeException e) {
            return empty(false, workflowAllowed, "profile-read-failed");
        }
        List<String> notes = new ArrayList<>();
        if (!snapshot.enabled()) {
            notes.add("profile-disabled");
        }
        if (!workflowAllowed) {
            notes.add("none".equals(option) ? "workflow-disabled" : "invalid-workflow-option");
        }
        List<PreferenceDecision> preferences = new ArrayList<>();
        Set<String> accepted = new HashSet<>();
        String responseStyle = null;
        String modelPolicy = null;
        boolean cheap = false;
        for (ProfilePreferencePort.Entry entry : snapshot.entries()) {
            String key = MemoryKeys.normalize(entry.key());
            String value = normalizedValue(entry.value());
            String reason = invalidReason(entry, key, value);
            if (reason == null && !snapshot.enabled()) {
                reason = "profile-disabled";
            }
            if (reason == null && !workflowAllowed) {
                reason = "none".equals(option) ? "workflow-disabled" : "invalid-workflow-option";
            }
            if (reason == null && !accepted.add(key)) {
                reason = "duplicate-slot";
            }
            boolean applied = reason == null;
            if (applied && RESPONSE_STYLE_KEY.equals(key)) {
                responseStyle = value;
                reason = "applied";
            } else if (applied) {
                modelPolicy = value;
                if ("cheap-first".equals(value) && model != null && !model.isBlank()) {
                    applied = false;
                    reason = "explicit-model";
                } else {
                    cheap = "cheap-first".equals(value);
                    reason = cheap ? "applied" : "configured-default-order";
                }
            }
            preferences.add(new PreferenceDecision(entry.memoryId(), key, entry.value(),
                    entry.kind() == null ? null : entry.kind().name(),
                    entry.source() == null ? null : entry.source().name(), applied, reason));
        }
        if (preferences.isEmpty() && snapshot.enabled() && workflowAllowed) {
            notes.add("no-explicit-preferences");
        }
        return new Decision(snapshot.enabled(), workflowAllowed, responseStyle, modelPolicy, cheap,
                stylePrompt(responseStyle), List.copyOf(preferences), List.copyOf(notes));
    }

    private static String invalidReason(ProfilePreferencePort.Entry entry, String key, String value) {
        if (!RESPONSE_STYLE_KEY.equals(key) && !MODEL_POLICY_KEY.equals(key)) {
            return "unsupported-key";
        }
        if (entry.kind() != MemoryKind.PREFERENCE) {
            return "not-preference";
        }
        if (entry.source() != MemorySource.MANUAL) {
            return "not-manual";
        }
        Set<String> allowed = RESPONSE_STYLE_KEY.equals(key) ? STYLES : POLICIES;
        return allowed.contains(value) ? null : "invalid-value";
    }

    private static String normalizedValue(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String stylePrompt(String style) {
        if (style == null) {
            return "";
        }
        String instruction = switch (style) {
            case "concise" -> "回复简洁，先给结论，只保留完成问题所需的说明。";
            case "balanced" -> "回复先给结论，再提供适量依据与必要步骤。";
            case "detailed" -> "回复提供完整解释、必要步骤与有助于理解的例子。";
            default -> "";
        };
        return instruction + "\n这是用户手工设置的回复风格；用户当前输入和工作流明确要求优先。";
    }

    private static Decision empty(boolean enabled, boolean workflowAllowed, String reason) {
        return new Decision(enabled, workflowAllowed, null, null, false, "", List.of(), List.of(reason));
    }

    public record Decision(boolean profileEnabled, boolean workflowAllowed, String responseStyle,
                           String modelPolicy, boolean preferCheapTiers, String stylePrompt,
                           List<PreferenceDecision> preferences, List<String> notes) {
    }

    public record PreferenceDecision(UUID memoryId, String key, String value, String kind, String source,
                                     boolean applied, String reason) {
    }
}
