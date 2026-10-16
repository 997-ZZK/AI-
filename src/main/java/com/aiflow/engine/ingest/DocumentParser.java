package com.aiflow.engine.ingest;

import com.aiflow.engine.attachment.ImageKind;
import com.aiflow.engine.common.Hashes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

/**
 * 文档解析：text / markdown / json（PDF 与图片由 {@link DocumentExtractor} 处理，这里只负责类型识别）。
 * JSON 会被拍平成 "路径: 值" 的行式文本，便于切片与引用定位。
 *
 * <p>{@link #detectType(String)} 的职责是**分诊**，不是解析：把能纯文本直读的留给这里、
 * 把需要二进制解析的（PDF）与需要模型的（图片）交给上游。所以它对「已知的二进制格式」明确报错，
 * 而不是当成 text 读进来 —— 后者会把一堆乱码切进向量库，且没人能一眼看出是怎么进去的。
 */
public class DocumentParser {

    /** 已知的二进制格式：与其按 UTF-8 读成乱码，不如明确说「请转成文本」。 */
    private static final List<String> BINARY_SUFFIXES = List.of(
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".zip", ".rar", ".7z", ".gz",
            ".exe", ".dll", ".so", ".dylib", ".bin", ".tiff", ".tif", ".bmp", ".ico");

    private final ObjectMapper objectMapper;

    public DocumentParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static DocumentSourceType detectType(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return DocumentSourceType.MARKDOWN;
        }
        if (name.endsWith(".json")) {
            return DocumentSourceType.JSON;
        }
        if (name.endsWith(".pdf")) {
            return DocumentSourceType.PDF;
        }
        if (ImageKind.hasImageExtension(name)) {
            return DocumentSourceType.IMAGE;
        }
        if (hasAnySuffix(name, BINARY_SUFFIXES)) {
            throw new UnsupportedDocumentException("这份文件需要先转成文本（当前支持 text / markdown / json / pdf / 图片）");
        }
        return DocumentSourceType.TEXT;
    }

    private static boolean hasAnySuffix(String name, List<String> suffixes) {
        for (String suffix : suffixes) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public ParsedDocument parse(String title, DocumentSourceType type, String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalArgumentException("文档内容为空");
        }
        String content = switch (type) {
            case TEXT, MARKDOWN -> rawContent;
            case JSON -> flattenJson(rawContent);
            // PDF / 图片没有「文本内容」可传：它们要走 DocumentExtractor（二进制 -> 文本），
            // 走到这里说明调用方把类型用错了，明确报出来而不是当成空文档入库
            case PDF, IMAGE -> throw new UnsupportedDocumentException(
                    type + " 需要按字节解析（见 DocumentExtractor），不能当纯文本入库");
        };
        return new ParsedDocument(title, type, content, Hashes.sha256Hex(content));
    }

    private String flattenJson(String rawContent) {
        try {
            JsonNode root = objectMapper.readTree(rawContent);
            StringBuilder builder = new StringBuilder();
            appendNode(builder, root, "");
            String flattened = builder.toString().stripTrailing();
            if (flattened.isBlank()) {
                throw new IllegalArgumentException("JSON 解析后没有可用文本");
            }
            return flattened;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败：内容不是合法 JSON");
        }
    }

    private void appendNode(StringBuilder builder, JsonNode node, String path) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> appendNode(builder, entry.getValue(),
                    path.isEmpty() ? entry.getKey() : path + "." + entry.getKey()));
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode item : node) {
                appendNode(builder, item, path + "[" + index + "]");
                index++;
            }
        } else if (node.isNull()) {
            return;
        } else {
            builder.append(path).append(": ").append(node.asText()).append('\n');
        }
    }
}
