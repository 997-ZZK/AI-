package com.aiflow.engine.api;

import com.aiflow.engine.api.service.KnowledgeBaseReindexService;
import com.aiflow.engine.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识库级操作（切片与文档在 DocumentController，检索在 RetrievalController）。
 * 后续 kb 级统计、删除也放这里。
 */
@RestController
@RequestMapping("/api/v1/kbs/{kbId}")
public class KnowledgeBaseController {

    private final KnowledgeBaseReindexService reindexService;

    public KnowledgeBaseController(KnowledgeBaseReindexService reindexService) {
        this.reindexService = reindexService;
    }

    /**
     * 用当前 embedding provider 重建该 kb 的索引。
     * 换 provider / 换模型之后必须跑一次，否则检索会因为向量空间不一致直接失败。
     */
    @PostMapping("/reindex")
    public ApiResponse<ReindexResponse> reindex(@PathVariable String kbId) {
        KnowledgeBaseReindexService.ReindexResult result = reindexService.reindex(kbId);
        return ApiResponse.ok(new ReindexResponse(result.kbId(), result.documents(), result.chunks(),
                result.embeddingModel(), result.previousModels()));
    }

    public record ReindexResponse(String kbId, long documents, int chunks, String embeddingModel,
                                  Map<String, Long> previousModels) {
    }
}