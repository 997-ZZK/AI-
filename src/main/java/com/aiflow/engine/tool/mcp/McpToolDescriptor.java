package com.aiflow.engine.tool.mcp;

import com.aiflow.engine.common.Hashes;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 从 MCP 服务端 {@code tools/list} 里发现的一个工具。
 *
 * <p>{@code fingerprint} 是「工具定义」的指纹（名字 + 描述 + 入参 schema）：
 * 服务端热更新后同名工具的实现可能已经变了，注册表里存的这条快照必须能证明「我记的是哪一版」。
 * 运行期调用前会拿它和当前快照比对，不一致就**明确失败**而不是按旧理解去调新工具。
 *
 * @param serverId    所属服务端
 * @param name        服务端内的工具名（调用时原样回传）
 * @param description 给模型/编排者看的一句话说明
 * @param inputSchema 入参 JSON Schema（原样保留，当前不校验，只随工具清单暴露）
 * @param fingerprint 定义指纹（sha256 前 12 位）
 */
public record McpToolDescriptor(String serverId, String name, String description, JsonNode inputSchema,
                                String fingerprint) {

    public McpToolDescriptor(String serverId, String name, String description, JsonNode inputSchema) {
        this(serverId, name, description, inputSchema, fingerprintOf(serverId, name, description, inputSchema));
    }

    /** 定义指纹：改名、改描述、改 schema 都算「换了一个工具」，三者都会改变调用方的预期。 */
    public static String fingerprintOf(String serverId, String name, String description, JsonNode inputSchema) {
        String schema = inputSchema == null ? "" : inputSchema.toString();
        String material = serverId + "|" + name + "|" + (description == null ? "" : description) + "|" + schema;
        return Hashes.sha256Hex(material).substring(0, 12);
    }
}
