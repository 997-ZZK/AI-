package com.aiflow.engine.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厂商私有请求体字段的解析与校验。
 *
 * <p>为什么需要它：OpenAI 兼容只覆盖协议主干，各家的关键开关是私有的 —— 火山方舟的 {@code thinking}
 * （推理链开关）、DashScope 的 {@code enable_thinking} 都在这一层。没有这个口子，每接一家厂商就要改一次
 * provider 代码；有了它，接厂商是改配置。
 *
 * <p>启动期解析并校验，不是每次请求：JSON 写错、写成数组、或试图覆盖 {@code model} / {@code messages} /
 * {@code stream} 都会让引擎起不来并说明原因。请求发出去之后才发现「参数没生效」是最花时间的排障。
 */
final class EndpointExtraBody {

    private static final List<String> RESERVED = List.of("model", "messages", "stream");

    private EndpointExtraBody() {
    }

    static Map<String, Object> parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readerFor(new TypeReference<LinkedHashMap<String, Object>>() {
            }).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
        } catch (Exception ignored) {
            // Jackson may include the configuration text in both its message and cause.
            throw new IllegalArgumentException("extra-body 不是合法 JSON 对象");
        }
        if (parsed == null) {
            throw new IllegalArgumentException("extra-body 不是合法 JSON 对象");
        }
        for (String reserved : RESERVED) {
            if (parsed.containsKey(reserved)) {
                throw new IllegalArgumentException("extra-body 不能覆盖 " + reserved + "：协议主干字段由引擎管理");
            }
        }
        if (parsed.containsValue(null)) {
            throw new IllegalArgumentException("extra-body 顶层字段不能为 null");
        }
        return Map.copyOf(parsed);
    }
}
