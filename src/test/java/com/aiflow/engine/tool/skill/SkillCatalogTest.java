package com.aiflow.engine.tool.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 技能库：扫描 SKILL.md、解析 frontmatter、按名检索、正文截断。 */
class SkillCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void writeSkill(Path root, String dir, String content) throws IOException {
        Path file = root.resolve(dir).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static SkillCatalog catalog(int maxBodyChars, int maxListEntries, Path... roots) {
        SkillToolProperties properties = new SkillToolProperties(true,
                java.util.Arrays.stream(roots).map(Path::toString).toList(), maxBodyChars, maxListEntries, 6);
        SkillCatalog catalog = new SkillCatalog(properties, MAPPER);
        catalog.start();
        return catalog;
    }

    @Test
    void scansRootsAndParsesFrontmatter(@TempDir Path root) throws IOException {
        writeSkill(root, "alpha", """
                ---
                name: alpha
                description: 第一个技能
                ---

                # Alpha
                正文 A
                """);
        writeSkill(root, "beta", """
                ---
                name: asu-skills:beta
                description: >
                  折叠描述第一行
                  第二行
                ---
                正文 B
                """);

        SkillCatalog catalog = catalog(20_000, 50, root);

        assertThat(catalog.all()).hasSize(2);
        assertThat(catalog.find("alpha")).isPresent();
        // 带命名空间的技能名用后缀匹配
        assertThat(catalog.find("beta")).isPresent();
        assertThat(catalog.find("beta").orElseThrow().name()).isEqualTo("asu-skills:beta");
        assertThat(catalog.find("beta").orElseThrow().description()).isEqualTo("折叠描述第一行 第二行");
        assertThat(catalog.find("gamma")).isEmpty();
        assertThat(catalog.readText("alpha", 0)).isEqualTo("# Alpha\n正文 A");
    }

    @Test
    void nameFallsBackToDirectoryAndMissingFrontmatterIsTolerated(@TempDir Path root) throws IOException {
        writeSkill(root, "no-front", "# 没有 frontmatter\n正文");

        SkillCatalog catalog = catalog(20_000, 50, root);

        assertThat(catalog.all()).hasSize(1);
        assertThat(catalog.all().get(0).name()).isEqualTo("no-front");
        assertThat(catalog.all().get(0).description()).isEmpty();
        assertThat(catalog.all().get(0).body()).startsWith("# 没有 frontmatter");
    }

    @Test
    void earlierRootWinsOnDuplicateName(@TempDir Path first, @TempDir Path second) throws IOException {
        writeSkill(first, "dup", """
                ---
                name: dup
                description: 先出现的
                ---
                first
                """);
        writeSkill(second, "dup", """
                ---
                name: dup
                description: 后出现的
                ---
                second
                """);

        SkillCatalog catalog = catalog(20_000, 50, first, second);

        assertThat(catalog.all()).hasSize(1);
        assertThat(catalog.find("dup").orElseThrow().description()).isEqualTo("先出现的");
        assertThat(catalog.readText("dup", 0)).isEqualTo("first");
    }

    @Test
    void readTextTruncatesAndSaysSo(@TempDir Path root) throws IOException {
        writeSkill(root, "long", """
                ---
                name: long
                description: 很长
                ---
                0123456789ABCDEF
                """);

        SkillCatalog catalog = catalog(10, 50, root);

        String text = catalog.readText("long", 0);
        assertThat(text).startsWith("0123456789");
        assertThat(text).contains("正文超长已截断").contains("全文 16 字符");
        // 显式传入的上限优先于配置
        assertThat(catalog.readText("long", 4)).startsWith("0123");
    }

    @Test
    void unknownSkillIsAnErrorNotAnEmptyString(@TempDir Path root) throws IOException {
        writeSkill(root, "alpha", """
                ---
                name: alpha
                ---
                正文
                """);
        SkillCatalog catalog = catalog(20_000, 50, root);

        assertThatThrownBy(() -> catalog.readText("nope", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("技能不存在");
    }

    @Test
    void listJsonFiltersByKeywordAndHonoursLimit(@TempDir Path root) throws Exception {
        writeSkill(root, "alpha", """
                ---
                name: alpha
                description: 简历制作
                ---
                a
                """);
        writeSkill(root, "beta", """
                ---
                name: beta
                description: 面试模拟
                ---
                b
                """);

        SkillCatalog catalog = catalog(20_000, 50, root);

        JsonNode filtered = MAPPER.readTree(catalog.listJson("面试", 10));
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).path("name").asText()).isEqualTo("beta");

        JsonNode limited = MAPPER.readTree(catalog.listJson("", 1));
        assertThat(limited).hasSize(1);
        assertThat(MAPPER.readTree(catalog.listJson("", 10))).hasSize(2);
    }

    @Test
    void disabledCatalogScansNothing(@TempDir Path root) throws IOException {
        writeSkill(root, "alpha", """
                ---
                name: alpha
                ---
                a
                """);
        SkillToolProperties properties = new SkillToolProperties(false, List.of(root.toString()), 1_000, 10, 4);
        SkillCatalog catalog = new SkillCatalog(properties, MAPPER);

        catalog.start();

        assertThat(catalog.enabled()).isFalse();
        assertThat(catalog.all()).isEmpty();
        assertThat(catalog.rescan()).isZero();
    }

    @Test
    void missingRootIsSkippedWithoutException(@TempDir Path root) {
        SkillCatalog catalog = catalog(20_000, 50, root.resolve("nope"));

        assertThat(catalog.all()).isEmpty();
    }
}
