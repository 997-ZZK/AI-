package com.aiflow.engine.node;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 节点参数模板渲染：把 ${var} 替换成运行变量值（变量缺失时替换为空串）。 */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");

    private TemplateRenderer() {
    }

    public static String render(String template, Map<String, Object> variables) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            Object value = variables.get(matcher.group(1));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
