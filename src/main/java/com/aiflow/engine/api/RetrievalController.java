package com.aiflow.engine.api;

import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.retrieval.RagRetrievalService;
import com.aiflow.engine.retrieval.RetrievalResult;
import com.aiflow.engine.retrieval.post.RetrievalStats;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索调试端点：直接看召回切片、分数、各阶段统计（不经过 LLM），用于联调与黄金集排查。
 */
@RestController
@RequestMapping("/api/v1/kbs/{kbId}")
public class RetrievalController {

    private final RagRetrievalService retrievalService;

    public RetrievalController(RagRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/retrieve")
    public ApiResponse<RetrieveResponse> retrieve(@PathVariable String kbId,
                                                  @Valid @RequestBody RetrieveRequest request) {
        Set<String> sources = request.sources() == null
                ? Set.of()
                : new LinkedHashSet<>(request.sources().stream().filter(item -> item != null && !item.isBlank()).toList());
        RetrievalResult result = retrievalService.retrieve(request.query(), kbId, sources, request.topK(),
                request.minScore());
        return ApiResponse.ok(new RetrieveResponse(result.query(), kbId, result.sources(), result.chunkViews(),
                result.citationLabels(), statsView(result.stats()),
                Boolean.TRUE.equals(request.includeContext()) ? result.contextBlock() : null));
    }

    private static Map<String, Object> statsView(RetrievalStats stats) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("raw", stats.raw());
        view.put("afterThreshold", stats.afterThreshold());
        view.put("afterTopK", stats.afterTopK());
        view.put("afterMmr", stats.afterMmr());
        view.put("afterMerge", stats.afterMerge());
        return view;
    }

    public record RetrieveRequest(
            @NotBlank String query,
            Integer topK,
            Double minScore,
            List<String> sources,
            Boolean includeContext
    ) {
    }

    public record RetrieveResponse(String query, String kbId, List<String> sources,
                                   List<Map<String, Object>> chunks, List<String> citations,
                                   Map<String, Object> stats, String contextBlock) {
    }
}
