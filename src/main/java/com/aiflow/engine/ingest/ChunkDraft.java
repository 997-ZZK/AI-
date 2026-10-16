package com.aiflow.engine.ingest;

import com.aiflow.engine.common.Hashes;

/** 切片草稿：入库前的切片表示（index 由切片器统一编号）。 */
public record ChunkDraft(
        int index,
        String sectionPath,
        Integer pageFrom,
        Integer pageTo,
        String content,
        int charCount,
        String contentHash
) {

    public static ChunkDraft of(int index, String sectionPath, Integer pageFrom, Integer pageTo, String content) {
        String normalized = content == null ? "" : content.strip();
        return new ChunkDraft(index, sectionPath, pageFrom, pageTo, normalized, normalized.length(),
                Hashes.sha256Hex(normalized));
    }
}
