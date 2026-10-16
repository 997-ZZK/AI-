package com.aiflow.engine.safety;

import com.aiflow.engine.config.SafetyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内容安全审核：输入（用户消息）与输出（模型回复）走同一套规则。
 *
 * <p>三条设计取舍：
 * <ul>
 *   <li>命中原因只回「类别」，不回命中的具体词：把词表回显给用户，等于把绕过词表的线索一并给出。
 *       具体命中项写进日志（DEBUG），给运维定位，不给调用方试探。</li>
 *   <li>超长直接拒绝，不做静默截断：用户以为发了全文、系统只处理了前 4000 字，是比报错更坏的结果。</li>
 *   <li>词表与正则都能外置到文件：真实词表动辄上千条，塞进 yml 会让配置文件的 diff 变成噪声。</li>
 * </ul>
 *
 * <p>这是规则层（可解释、可回归、零延迟），不是模型层审核；两者是互补关系，不是替代关系。
 */
@Service
public class ContentSafety {

    private static final Logger log = LoggerFactory.getLogger(ContentSafety.class);

    public enum Direction {
        INPUT, OUTPUT
    }

    public record Verdict(boolean allowed, String category, String reason) {

        public static Verdict allow() {
            return new Verdict(true, null, null);
        }

        public static Verdict block(String category, String reason) {
            return new Verdict(false, category, reason);
        }
    }

    private final SafetyProperties properties;
    private final List<String> keywords;
    private final List<Pattern> patterns;

    public ContentSafety(SafetyProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        List<String> loaded = new ArrayList<>();
        properties.keywords().forEach(keyword -> addKeyword(loaded, keyword));
        loadKeywordsFile(resourceLoader, properties.keywordsFile()).forEach(keyword -> addKeyword(loaded, keyword));
        this.keywords = List.copyOf(loaded);
        this.patterns = compile(properties.patterns());
        log.info("内容安全审核已装配: enabled={} 关键词={} 条 正则={} 条", properties.enabled(), keywords.size(),
                patterns.size());
    }

    public Verdict review(String text, Direction direction) {
        if (!properties.enabled() || text == null || text.isBlank()) {
            return Verdict.allow();
        }
        int limit = direction == Direction.INPUT ? properties.maxInputChars() : properties.maxOutputChars();
        if (text.length() > limit) {
            String label = direction == Direction.INPUT ? "输入" : "输出";
            return Verdict.block("长度", label + "长度 " + text.length() + " 超过上限 " + limit + " 字符");
        }
        return scan(text, direction == Direction.INPUT ? "输入" : "输出");
    }

    /**
     * 只跑敏感词与正则（**不含长度规则**）：给流式增量审核用。
     *
     * <p>长度是「整段文本」的属性，在片段上判定没有意义 —— 每个片段都短，只有最后一段（或完整回复）才具决定性；
     * 把长度规则放进增量检查，只会让每个片段都被误判。所以增量层只管「命中即可判定」的两类规则，
     * 长度与兜底仍由 {@link #review(String, Direction)} 对完整回复执行。
     */
    public Verdict scanRules(String text) {
        if (!properties.enabled() || text == null || text.isBlank()) {
            return Verdict.allow();
        }
        return scan(text, "增量");
    }

    /** 最长关键词的字符数：增量审核的保留窗口要不小于它，才能保证命中词在推送前被拦下。 */
    public int longestKeywordLength() {
        return keywords.stream().mapToInt(String::length).max().orElse(0);
    }

    private Verdict scan(String text, String label) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                log.debug("内容审核命中关键词（{}）: {}", label, keyword);
                return Verdict.block("敏感词", "命中敏感词规则");
            }
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                log.debug("内容审核命中正则（{}）: {}", label, pattern.pattern());
                return Verdict.block("违规模式", "命中违规模式规则");
            }
        }
        return Verdict.allow();
    }

    /** 供状态接口展示：当前生效的词表与规则规模（不暴露内容）。 */
    public List<String> keywordSummary() {
        return keywords;
    }

    public List<Pattern> patterns() {
        return patterns;
    }

    private static void addKeyword(List<String> target, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            target.add(keyword.strip().toLowerCase(Locale.ROOT));
        }
    }

    private static List<String> loadKeywordsFile(ResourceLoader resourceLoader, String location) {
        if (location == null || location.isBlank()) {
            return List.of();
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("敏感词文件不存在，跳过: {}", location);
            return List.of();
        }
        try (InputStream input = resource.getInputStream()) {
            List<String> keywords = new ArrayList<>();
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    keywords.add(trimmed);
                }
            }
            return keywords;
        } catch (IOException e) {
            log.error("敏感词文件读取失败: {}", location, e);
            return List.of();
        }
    }

    private static List<Pattern> compile(List<String> rawPatterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String raw : rawPatterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(raw));
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException("内容安全正则非法: " + raw + " -> " + e.getDescription(), e);
            }
        }
        return List.copyOf(compiled);
    }
}