package com.aiflow.engine.ingest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知切片器：
 * 1. 按 markdown 标题维护章节栈，切片的 sectionPath 记录标题路径（引用可定位到章节）；
 * 2. 标题行不单独成块，而是拼到下一段正文前面，避免首块只含标题、sectionPath 丢掉子章节；
 * 3. 空行分段，段落累积到目标长度产出切片，切片之间保留尾部重叠，避免答案被切断；
 * 4. 只由重叠尾巴构成的尾块会被丢弃（内容已在上一切片里），不产出无信息碎片；
 * 5. 超长段落按句末标点硬切，单句超长再按字符硬切。
 */
public final class TextChunker {

    public static final int DEFAULT_TARGET_CHARS = 400;
    public static final int DEFAULT_OVERLAP_CHARS = 80;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    private final int targetChars;
    private final int overlapChars;
    private final int maxPieceChars;

    public TextChunker() {
        this(DEFAULT_TARGET_CHARS, DEFAULT_OVERLAP_CHARS);
    }

    public TextChunker(int targetChars, int overlapChars) {
        if (targetChars <= 0) {
            throw new IllegalArgumentException("targetChars 必须为正整数: " + targetChars);
        }
        if (overlapChars < 0 || overlapChars >= targetChars) {
            throw new IllegalArgumentException("overlapChars 必须落在 [0, targetChars): " + overlapChars);
        }
        this.targetChars = targetChars;
        this.overlapChars = overlapChars;
        this.maxPieceChars = Math.max(targetChars, targetChars * 3);
    }

    /**
     * 按页切片：每页独立走 {@link #chunk(String)}，产出的切片带上页码。
     *
     * <p>为什么不把各页拼起来一起切：页码是 PDF 引用的唯一定位方式，一旦跨页合并就说不清「这条来自哪页」。
     * 代价是跨页的句子会被拆成两条切片 —— 对检索没有实质损失（两条都能被召回），
     * 而「引用页码错一位」是会让人白翻一页的错误。
     */
    public List<ChunkDraft> chunkPages(List<PageText> pages) {
        List<ChunkDraft> result = new ArrayList<>();
        int index = 0;
        for (PageText page : pages) {
            if (page.blank()) {
                continue;
            }
            for (ChunkDraft draft : chunk(page.text())) {
                result.add(ChunkDraft.of(index++, draft.sectionPath(), page.pageNumber(), page.pageNumber(),
                        draft.content()));
            }
        }
        return result;
    }

    public List<ChunkDraft> chunk(String text) {
        List<ChunkDraft> drafts = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return drafts;
        }
        StringBuilder buffer = new StringBuilder();
        String bufferSection = null;
        boolean bufferIsOverlapOnly = false;
        for (Block block : blocks(text)) {
            for (String piece : splitLongParagraph(block.text())) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                if (bufferSection == null) {
                    bufferSection = block.sectionPath();
                }
                bufferIsOverlapOnly = false;
                buffer.append(piece);
                if (buffer.length() >= targetChars) {
                    drafts.add(ChunkDraft.of(drafts.size(), bufferSection, null, null, buffer.toString()));
                    String overlap = tail(buffer.toString());
                    buffer.setLength(0);
                    bufferSection = null;
                    if (!overlap.isEmpty()) {
                        buffer.append(overlap);
                        bufferIsOverlapOnly = true;
                    }
                }
            }
        }
        if (!bufferIsOverlapOnly && !buffer.toString().isBlank()) {
            drafts.add(ChunkDraft.of(drafts.size(), bufferSection, null, null, buffer.toString()));
        }
        return drafts;
    }

    private List<Block> blocks(String text) {
        List<Block> blocks = new ArrayList<>();
        ArrayDeque<String> headings = new ArrayDeque<>();
        StringBuilder paragraph = new StringBuilder();
        StringBuilder pendingHeadings = new StringBuilder();
        for (String rawLine : text.split("\r?\n", -1)) {
            String line = rawLine.strip();
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flush(blocks, paragraph, headings);
                int level = heading.group(1).length();
                while (headings.size() >= level) {
                    headings.removeLast();
                }
                headings.addLast(heading.group(2).strip());
                appendLine(pendingHeadings, line);
                continue;
            }
            if (line.isEmpty()) {
                flush(blocks, paragraph, headings);
                continue;
            }
            if (paragraph.length() == 0 && pendingHeadings.length() > 0) {
                paragraph.append(pendingHeadings);
                pendingHeadings.setLength(0);
            }
            appendLine(paragraph, line);
        }
        flush(blocks, paragraph, headings);
        if (pendingHeadings.length() > 0) {
            flush(blocks, pendingHeadings, headings);
        }
        return blocks;
    }

    private static void appendLine(StringBuilder target, String line) {
        if (target.length() > 0) {
            target.append('\n');
        }
        target.append(line);
    }

    private void flush(List<Block> blocks, StringBuilder paragraph, ArrayDeque<String> headings) {
        if (paragraph.length() == 0) {
            return;
        }
        String section = headings.isEmpty() ? null : String.join(" > ", headings);
        blocks.add(new Block(section, paragraph.toString()));
        paragraph.setLength(0);
    }

    private List<String> splitLongParagraph(String paragraph) {
        List<String> pieces = new ArrayList<>();
        if (paragraph.length() <= maxPieceChars) {
            pieces.add(paragraph);
            return pieces;
        }
        String[] sentences = paragraph.split("(?<=[。！？!?；;\\n])");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() > 0 && current.length() + sentence.length() > maxPieceChars) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (sentence.length() > maxPieceChars) {
                int from = 0;
                while (from < sentence.length()) {
                    int to = Math.min(sentence.length(), from + maxPieceChars);
                    pieces.add(sentence.substring(from, to));
                    from = to;
                }
                continue;
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private String tail(String content) {
        if (overlapChars == 0 || content.length() <= overlapChars) {
            return "";
        }
        return content.substring(content.length() - overlapChars).stripLeading();
    }

    private record Block(String sectionPath, String text) {
    }
}
