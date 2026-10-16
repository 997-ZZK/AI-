package com.aiflow.engine.node.builtin;

import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.TemplateRenderer;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.retrieval.RagRetrievalService;
import com.aiflow.engine.retrieval.RetrievalException;
import com.aiflow.engine.retrieval.RetrievalResult;
import com.aiflow.engine.retrieval.post.RetrievalStats;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * retrieval.search 节点：查知识库并把「编号资料 + 引用要求 + 防注入声明」写进 ${context}。
 *
 * 参数：query（模板，默认 ${input}）、kbId、topK、minScore、sources（"local,cloud" 或数组）。
 * 输出：context / chunks / citations / sources / retrievalStats（都进 node_runs，引用可溯源）。
 */
@Component
public class RetrievalNodeExecutor implements NodeExecutor {

    private final RagRetrievalService retrievalService;
    private final RetrievalProperties properties;

    public RetrievalNodeExecutor(RagRetrievalService retrievalService, RetrievalProperties properties) {
        this.retrievalService = retrievalService;
        this.properties = properties;
    }

    @Override
    public String type() {
        return "retrieval.search";
    }

    @Override
    public Set<String> requiredCapabilities() {
        return Set.of(Capabilities.EMBEDDING);
    }

    @Override
    public NodeResult execute(NodeContext context) {
        String query = TemplateRenderer.render(context.paramString("query", "${input}"), context.variables()).strip();
        if (query.isEmpty()) {
            return NodeResult.failed("检索查询为空：请检查 query 模板与输入变量");
        }
        String kbId = context.paramString("kbId", "default");
        Integer topK = intParam(context, "topK");
        Double minScore = doubleParam(context, "minScore");
        Set<String> sources = sourcesParam(context);
        try {
            RetrievalResult result = retrievalService.retrieve(query, kbId, sources, topK, minScore);
            List<Map<String, Object>> chunkViews = result.chunkViews();
            context.setVariable("context", result.contextBlock());
            context.setVariable("chunks", chunkViews);
            context.setVariable("citations", result.citationLabels());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("context", result.contextBlock());
            output.put("chunks", chunkViews);
            output.put("citations", result.citationLabels());
            output.put("sources", result.sources());
            output.put("retrievalStats", statsView(result.stats()));
            return NodeResult.success(output);
        } catch (RetrievalException e) {
            return NodeResult.failed(e.getMessage());
        }
    }

    private Map<String, Object> statsView(RetrievalStats stats) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("raw", stats.raw());
        view.put("afterThreshold", stats.afterThreshold());
        view.put("afterTopK", stats.afterTopK());
        view.put("afterMmr", stats.afterMmr());
        view.put("afterMerge", stats.afterMerge());
        return view;
    }

    private Integer intParam(NodeContext context, String key) {
        long value = context.paramLong(key, -1);
        return value > 0 ? (int) value : null;
    }

    private Double doubleParam(NodeContext context, String key) {
        Object value = context.param(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Set<String> sourcesParam(NodeContext context) {
        Object value = context.param("sources");
        if (value == null) {
            return Set.copyOf(new LinkedHashSet<>(List.of(properties.sources())));
        }
        Set<String> sources = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> sources.add(String.valueOf(item).strip()));
        } else {
            for (String item : String.valueOf(value).split(",")) {
                if (!item.isBlank()) {
                    sources.add(item.strip());
                }
            }
        }
        sources.removeIf(String::isBlank);
        return sources;
    }
}
