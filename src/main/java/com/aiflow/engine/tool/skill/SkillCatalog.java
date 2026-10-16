package com.aiflow.engine.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本机技能库：扫描 {@code SKILL.md}（Codex / Claude 的技能目录格式），把「技能」变成可编排的知识源。
 *
 * <p>只暴露两个工具，而不是「一个技能一个工具」：
 * <ul>
 *   <li>{@code skill.list}：按关键词列出可用技能（名字 + 描述），让编排者/模型先看有什么</li>
 *   <li>{@code skill.read}：读某个技能的正文（frontmatter 已剥离，超长截断）</li>
 * </ul>
 * 一个技能一个工具会让工具清单随技能数量线性膨胀（本机就有 40+ 个），而工作流里真正需要的是
 * 「先检索、再取用」这两步；多出来的每一步都得在提示词里解释一遍，得不偿失。
 *
 * <p>扫描是**启动时一次**，文件改动后调 {@link #rescan()}（控制台/接口可触发），不做文件监听：
 * 监听会引入 inotify 句柄与跨平台差异，而技能目录变更频率极低。
 */
@Component
public class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);
    private static final String SKILL_FILE = "SKILL.md";
    private static final int MAX_SKILLS = 500;

    private final SkillToolProperties properties;
    private final ObjectMapper mapper;
    private final List<Path> roots = new ArrayList<>();
    private volatile Map<String, Skill> skills = Map.of();

    public SkillCatalog(SkillToolProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        for (String root : properties.roots()) {
            if (root != null && !root.isBlank()) {
                roots.add(Path.of(expandHome(root.strip())));
            }
        }
    }

    @PostConstruct
    public void start() {
        if (!properties.enabled()) {
            log.info("技能库未启用（aiflow.tools.skills.enabled=false），跳过扫描");
            return;
        }
        rescan();
    }

    /** 重新扫描全部根目录，返回发现的技能数。同名技能以先出现的根目录为准。 */
    public synchronized int rescan() {
        if (!properties.enabled()) {
            log.info("技能库未启用，跳过重新扫描");
            skills = Map.of();
            return 0;
        }
        Map<String, Skill> found = new LinkedHashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                log.warn("技能根目录不存在，跳过: {}", root);
                continue;
            }
            int before = found.size();
            try (Stream<Path> stream = Files.walk(root, Math.max(1, properties.maxDepth()))) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> SKILL_FILE.equalsIgnoreCase(path.getFileName().toString()))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
                for (Path file : files) {
                    if (found.size() >= MAX_SKILLS) {
                        log.warn("技能数超过 {}，其余被忽略（先调小扫描根目录）", MAX_SKILLS);
                        break;
                    }
                    readSkill(file, root).ifPresent(skill -> found.putIfAbsent(skill.name().toLowerCase(Locale.ROOT), skill));
                }
            } catch (IOException e) {
                log.warn("技能根目录扫描失败 {}: {}", root, e.getMessage());
            }
            log.info("技能根目录 {} 新增 {} 个技能", root, found.size() - before);
        }
        skills = Map.copyOf(found);
        log.info("技能库装配完成: {} 个技能（根目录 {} 个）", skills.size(), roots.size());
        return skills.size();
    }

    private Optional<Skill> readSkill(Path file, Path root) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            log.debug("技能文件读取失败，跳过 {}: {}", file, e.getMessage());
            return Optional.empty();
        }
        Map<String, String> front = parseFrontmatter(text);
        String name = front.getOrDefault("name", "").strip();
        if (name.isEmpty()) {
            Path parent = file.getParent();
            name = parent == null ? "" : parent.getFileName().toString();
        }
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Skill(name, front.getOrDefault("description", "").strip(),
                stripFrontmatter(text).strip(), file, root.toString()));
    }

    /** 解析 {@code ---} 之间的 frontmatter。只认单行 {@code key: value} 与 {@code key: >} 折叠块。 */
    static Map<String, String> parseFrontmatter(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            return values;
        }
        int i = 1;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.strip().equals("---")) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).strip();
                if (value.equals(">") || value.equals("|")) {
                    StringBuilder folded = new StringBuilder();
                    int j = i + 1;
                    while (j < lines.size() && (lines.get(j).startsWith(" ") || lines.get(j).startsWith("\t"))) {
                        folded.append(lines.get(j).strip()).append(' ');
                        j++;
                    }
                    value = folded.toString().strip();
                    i = j;
                } else {
                    i++;
                }
                values.put(key, unquote(value));
                continue;
            }
            i++;
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 去掉 frontmatter，只留正文（正文才是要给模型看的部分）。 */
    static String stripFrontmatter(String text) {
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            return text;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                return String.join("\n", lines.subList(i + 1, lines.size()));
            }
        }
        return text;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    public List<Path> roots() {
        return List.copyOf(roots);
    }

    /** 精确名优先；否则唯一后缀匹配（技能目录里常见 {@code asu-skills:interview} 这种带前缀的名字）。 */
    public Optional<Skill> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String needle = name.strip().toLowerCase(Locale.ROOT);
        Skill exact = skills.get(needle);
        if (exact != null) {
            return Optional.of(exact);
        }
        List<Skill> matches = skills.values().stream()
                .filter(skill -> skill.name().toLowerCase(Locale.ROOT).endsWith(":" + needle)
                        || skill.name().toLowerCase(Locale.ROOT).equals(needle))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    public String listJson(String query, int limit) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        int capped = Math.max(1, Math.min(limit <= 0 ? properties.maxListEntries() : limit, properties.maxListEntries()));
        ArrayNode array = mapper.createArrayNode();
        for (Skill skill : all()) {
            if (!needle.isEmpty()
                    && !skill.name().toLowerCase(Locale.ROOT).contains(needle)
                    && !skill.description().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (array.size() >= capped) {
                break;
            }
            ObjectNode node = array.addObject();
            node.put("name", skill.name());
            node.put("description", abbreviate(skill.description(), 300));
            node.put("root", skill.root());
            node.put("path", skill.path().toString());
        }
        return array.toString();
    }

    public String readText(String name, int maxCharsOverride) {
        Skill skill = find(name).orElseThrow(() -> new IllegalArgumentException(
                "技能不存在: " + name + "（用 skill.list 查可用名字）"));
        int cap = maxCharsOverride > 0 ? maxCharsOverride : properties.maxBodyChars();
        String body = skill.body();
        if (body.length() > cap) {
            return body.substring(0, cap) + "\n\n…（正文超长已截断，全文 " + body.length() + " 字符，见 "
                    + skill.path() + "）";
        }
        return body;
    }

    private static String abbreviate(String value, int max) {
        String flat = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String expandHome(String path) {
        return path.startsWith("~") ? System.getProperty("user.home") + path.substring(1) : path;
    }

    /**
     * @param name        技能名（frontmatter 的 name，缺省用目录名）
     * @param description frontmatter 的 description
     * @param body        SKILL.md 正文（已剥离 frontmatter）
     * @param path        SKILL.md 的绝对路径
     * @param root        命中的扫描根目录
     */
    public record Skill(String name, String description, String body, Path path, String root) {
    }
}
