package com.aiflow.engine.retrieval;

/**
 * 一个召回的切片。score 为归一化后的分数（0..1，越大越相关），rawScore 保留来源原始分数便于排查。
 *
 * 注意：record 的 equals/hashCode 对 {@code float[] embedding} 是引用比较，向量只做数值使用，不要拿切片做集合去重
 * （去重请用 contentHash / chunkId）。
 */
public record RetrievedChunk(
        String source,
        String docId,
        String docTitle,
        String chunkId,
        int chunkIndex,
        String sectionPath,
        Integer pageFrom,
        Integer pageTo,
        String text,
        String contentHash,
        double rawScore,
        double score,
        float[] embedding
) {

    public RetrievedChunk withScore(double normalizedScore, double originalRawScore) {
        return new RetrievedChunk(source, docId, docTitle, chunkId, chunkIndex, sectionPath, pageFrom, pageTo,
                text, contentHash, originalRawScore, normalizedScore, embedding);
    }

    public RetrievedChunk withEmbedding(float[] vector) {
        return new RetrievedChunk(source, docId, docTitle, chunkId, chunkIndex, sectionPath, pageFrom, pageTo,
                text, contentHash, rawScore, score, vector);
    }

    /** 相邻切片合并：文本拼接，页码取并集，分数取较大者，chunkIndex 沿用先出现的那个。 */
    public RetrievedChunk mergedWith(RetrievedChunk next, String mergedText) {
        return new RetrievedChunk(source, docId, docTitle, chunkId, chunkIndex, sectionPath,
                minPage(pageFrom, next.pageFrom()), maxPage(pageTo, next.pageTo()),
                mergedText, contentHash,
                Math.max(rawScore, next.rawScore()), Math.max(score, next.score()), embedding);
    }

    public boolean sameDocument(RetrievedChunk other) {
        return other != null && docId != null && docId.equals(other.docId());
    }

    public String citationLabel() {
        StringBuilder label = new StringBuilder();
        label.append(docTitle == null || docTitle.isBlank() ? String.valueOf(docId) : docTitle);
        String path = trimmedSectionPath();
        if (!path.isEmpty()) {
            label.append(" > ").append(path);
        }
        if (pageFrom != null) {
            label.append(" p.").append(pageFrom);
            if (pageTo != null && !pageTo.equals(pageFrom)) {
                label.append('-').append(pageTo);
            }
        }
        return label.toString();
    }

    /**
     * 去掉与文档标题重复的开头段：markdown 的 H1 会进标题栈，导致 sectionPath 以标题开头，
     * 渲染出来就是「报销制度.md > 报销制度 > 发票要求」这种重复引用。
     */
    private String trimmedSectionPath() {
        if (sectionPath == null || sectionPath.isBlank()) {
            return "";
        }
        String path = sectionPath.strip();
        String title = docTitle == null ? "" : docTitle.strip();
        String titleWithoutExtension = title.replaceFirst("\\.[A-Za-z0-9]{1,8}$", "");
        for (String candidate : java.util.List.of(title, titleWithoutExtension)) {
            if (candidate.isEmpty()) {
                continue;
            }
            if (path.equals(candidate)) {
                return "";
            }
            String prefix = candidate + " > ";
            if (path.startsWith(prefix)) {
                return path.substring(prefix.length());
            }
        }
        return path;
    }

    private static Integer minPage(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.min(left, right);
    }

    private static Integer maxPage(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }
}
