package com.aiflow.engine.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 测试用的最小 MCP 服务端（stdio，newline-delimited JSON-RPC）。
 *
 * <p>为什么不引一个真实的 MCP 服务端做测试：真实服务端要么要联网装包（npx / uvx），
 * 要么依赖具体运行时，把「客户端协议对不对」这件事和「环境装没装」绑在一起。
 * 这个夹具只实现协议本身，让测试能在任何机器上离线跑，也顺便当成一份「最小服务端长什么样」的样例。
 *
 * <p>提供的工具：{@code echo}（回显入参）、{@code boom}（返回 isError）、{@code slow}（睡 5 秒，测超时）。
 * 另外把一行诊断信息写到 stderr —— 测试要验证「stderr 尾部会出现在错误消息里」。
 */
public final class FakeMcpServerMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeMcpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        System.err.println("fake-mcp-server: started");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode request = MAPPER.readTree(line);
                JsonNode id = request.get("id");
                String method = request.path("method").asText("");
                if (id == null || id.isNull()) {
                    continue;
                }
                ObjectNode response = MAPPER.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", id);
                switch (method) {
                    case "initialize" -> {
                        ObjectNode result = response.putObject("result");
                        result.put("protocolVersion", "2024-11-05");
                        result.putObject("capabilities").putObject("tools");
                        result.putObject("serverInfo").put("name", "fake-mcp").put("version", "0.1.0");
                    }
                    case "tools/list" -> response.set("result", toolsList());
                    case "tools/call" -> response.set("result", callTool(request.path("params")));
                    default -> {
                        ObjectNode error = response.putObject("error");
                        error.put("code", -32601);
                        error.put("message", "Method not found: " + method);
                    }
                }
                writer.write(MAPPER.writeValueAsString(response));
                writer.write('\n');
                writer.flush();
            }
        }
    }

    private static ObjectNode toolsList() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.addObject().put("name", "echo").put("description", "原样回显入参 msg")
                .putObject("inputSchema").put("type", "object");
        tools.addObject().put("name", "boom").put("description", "总是返回 isError=true")
                .putObject("inputSchema").put("type", "object");
        tools.addObject().put("name", "slow").put("description", "睡 5 秒后返回（用于超时测试）")
                .putObject("inputSchema").put("type", "object");
        return result;
    }

    private static ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        String argument = params.path("arguments").path("msg").asText("");
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        switch (name) {
            case "echo" -> content.addObject().put("type", "text").put("text", "echo:" + argument);
            case "boom" -> {
                result.put("isError", true);
                content.addObject().put("type", "text").put("text", "boom:" + argument);
            }
            case "slow" -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                content.addObject().put("type", "text").put("text", "slow done");
            }
            default -> {
                result.put("isError", true);
                content.addObject().put("type", "text").put("text", "unknown tool: " + name);
            }
        }
        return result;
    }
}
