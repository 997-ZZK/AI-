package com.aiflow.engine.integration;

import com.aiflow.engine.config.RetrievalProperties;
import com.aiflow.engine.retrieval.RetrievalQuery;
import com.aiflow.engine.retrieval.RetrievedChunk;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static com.aiflow.engine.integration.CloudKnowledgeBaseException.Reason.CONFIGURATION;
import static com.aiflow.engine.integration.CloudKnowledgeBaseException.Reason.INVALID_RESPONSE;

/** 有界 JSON 字段映射，不执行表达式、脚本或模板。分数仍为厂商原始量纲。 */
public final class JsonKnowledgeBaseProtocol implements KnowledgeBaseProtocol {
    private final RetrievalProperties.CloudMapping mapping;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonKnowledgeBaseProtocol(RetrievalProperties.CloudMapping mapping) {
        this.mapping = mapping;
        if (mapping == null || !field(mapping.datasetField()) || !field(mapping.queryField())
                || !field(mapping.topKField()) || mapping.datasetField().equals(mapping.queryField())
                || mapping.datasetField().equals(mapping.topKField()) || mapping.queryField().equals(mapping.topKField())) {
            throw failure(CONFIGURATION);
        }
        try {
            for (String pointer : List.of(mapping.chunksPointer(), mapping.chunkIdPointer(), mapping.documentIdPointer(),
                    mapping.titlePointer(), mapping.contentPointer(), mapping.scorePointer(), mapping.chunkIndexPointer(),
                    mapping.sectionPointer(), mapping.pageFromPointer(), mapping.pageToPointer(), mapping.contentHashPointer())) {
                if (!pointer.startsWith("/") || pointer.length() > 512) throw failure(CONFIGURATION);
                JsonPointer.compile(pointer);
            }
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw failure(CONFIGURATION);
        }
    }

    @Override
    public byte[] encode(RetrievalQuery query, String datasetId) {
        ObjectNode body = mapper.createObjectNode();
        body.put(mapping.datasetField(), datasetId);
        body.put(mapping.queryField(), query.text());
        body.put(mapping.topKField(), query.topK());
        try {
            return mapper.writeValueAsBytes(body);
        } catch (java.io.IOException ex) {
            throw failure(CONFIGURATION);
        }
    }

    @Override
    public List<RetrievedChunk> decode(String source, byte[] response) {
        try {
            JsonNode root = mapper.readTree(response);
            if (root == null) throw failure(INVALID_RESPONSE);
            JsonNode hits = root.at(mapping.chunksPointer());
            if (!hits.isArray()) throw failure(INVALID_RESPONSE);
            List<RetrievedChunk> result = new ArrayList<>(hits.size());
            for (JsonNode hit : hits) {
                String id = requiredText(hit, mapping.chunkIdPointer());
                String docId = requiredText(hit, mapping.documentIdPointer());
                String text = requiredText(hit, mapping.contentPointer());
                JsonNode scoreNode = hit.at(mapping.scorePointer());
                if (!scoreNode.isNumber() || !Double.isFinite(scoreNode.doubleValue())) throw failure(INVALID_RESPONSE);
                double score = scoreNode.doubleValue();
                Integer chunkIndex = optionalInteger(hit, mapping.chunkIndexPointer(), 0);
                Integer pageFrom = optionalInteger(hit, mapping.pageFromPointer(), 1);
                Integer pageTo = optionalInteger(hit, mapping.pageToPointer(), 1);
                if (pageFrom != null && pageTo != null && pageFrom > pageTo) throw failure(INVALID_RESPONSE);
                String title = optionalText(hit, mapping.titlePointer());
                String hash = optionalText(hit, mapping.contentHashPointer());
                result.add(new RetrievedChunk(source, docId, title == null ? docId : title, id,
                        chunkIndex == null ? -1 : chunkIndex, optionalText(hit, mapping.sectionPointer()),
                        pageFrom, pageTo, text, hash == null ? sha256(text) : hash, score, score, null));
            }
            return List.copyOf(result);
        } catch (java.io.IOException | IllegalArgumentException ex) {
            throw failure(INVALID_RESPONSE);
        }
    }

    private static String requiredText(JsonNode hit, String pointer) {
        String value = optionalText(hit, pointer);
        if (value == null) throw failure(INVALID_RESPONSE);
        return value;
    }

    private static String optionalText(JsonNode hit, String pointer) {
        JsonNode node = hit.at(pointer);
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isTextual()) throw failure(INVALID_RESPONSE);
        return node.textValue().isBlank() ? null : node.textValue();
    }

    private static Integer optionalInteger(JsonNode hit, String pointer, int minimum) {
        JsonNode node = hit.at(pointer);
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < minimum) throw failure(INVALID_RESPONSE);
        return node.intValue();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static boolean field(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}");
    }

    private static CloudKnowledgeBaseException failure(CloudKnowledgeBaseException.Reason reason) {
        return new CloudKnowledgeBaseException(reason, null, false);
    }
}
