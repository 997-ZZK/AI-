package com.aiflow.engine.integration;

import com.aiflow.engine.retrieval.RetrievalQuery;
import com.aiflow.engine.retrieval.RetrievedChunk;

import java.util.List;

/** 厂商协议映射端口。传输、鉴权与重试由 HTTP provider 负责。 */
public interface KnowledgeBaseProtocol {
    byte[] encode(RetrievalQuery query, String datasetId);
    List<RetrievedChunk> decode(String source, byte[] response);
}
