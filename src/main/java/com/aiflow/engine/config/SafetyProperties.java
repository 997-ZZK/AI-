package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 内容安全审核配置。
 *
 * @param enabled       总开关（关掉 = 只做长度截断以外的空操作，用于回归对比）
 * @param maxInputChars 输入长度上限，超长直接拒绝（不做静默截断：用户不知道被截了才是最糟的）
 * @param maxOutputChars 输出长度上限，超长按违规处理并给预设话术
 * @param rejectedReply 命中审核时返回给用户的预设话术
 * @param keywords      敏感词（子串匹配，ASCII 不区分大小写）
 * @param keywordsFile  敏感词文件（一行一个，# 开头为注释）；真实词表放这里，别塞进 yml
 * @param patterns      违规正则（提示词注入、越狱句式等）
 * @param streaming     流式增量审核（见内层 record）
 */
@ConfigurationProperties(prefix = "aiflow.safety")
public record SafetyProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("4000") int maxInputChars,
        @DefaultValue("8000") int maxOutputChars,
        @DefaultValue("抱歉，这条内容没有通过内容安全审核，已为你拦截。") String rejectedReply,
        List<String> keywords,
        @DefaultValue("") String keywordsFile,
        List<String> patterns,
        @DefaultValue Streaming streaming
) {

    public SafetyProperties {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        streaming = streaming == null ? new Streaming(true, 32, true, 200) : streaming;
    }

    public static SafetyProperties defaults() {
        return new SafetyProperties(true, 4_000, 8_000,
                "抱歉，这条内容没有通过内容安全审核，已为你拦截。", List.of(), "", List.of(),
                new Streaming(true, 32, true, 200));
    }

    /**
     * 流式增量审核：模型一边生成、审核一边跑，命中的内容**在推送之前**被拦下。
     *
     * <p>做法是保留窗口（hold back）：最近 N 个字符先不推送，等下一批片段到达时连同窗口一起检查，
     * 于是「命中的片段已经发出去了」这件事在窗口之内不会发生。代价是最多延迟这么多字符的输出。
     *
     * <p>窗口默认**不是**手填的那一个数：{@code autoWindow=true} 时会取「{@code holdBackChars} 与最长敏感词长度」
     * 的较大值 —— 词表里放一个比窗口还长的词，等于给自己留了一条「先推送、后拦截」的缝；
     * 自动抬高把这条缝按词表实际情况补上（代价只是多延迟几个字符）。上限 {@code maxAutoWindow} 用来挡
     * 病态词表（有人往词表里粘了一整段话）：超过上限就只保证到上限为止，并记 WARN 明说哪些长度拦不住。
     *
     * @param enabled       开关；关掉则片段原样透传（等于回到「只审完整回复」）
     * @param holdBackChars 保留窗口的下限（autoWindow=true 时会被最长敏感词抬高）
     * @param autoWindow    是否按最长敏感词自动抬高窗口（默认 true；false = 窗口就是 holdBackChars，做实验用）
     * @param maxAutoWindow 自动抬高的上限（默认 200 字符；再长的「词」更可能是误粘的整段文本）
     */
    public record Streaming(@DefaultValue("true") boolean enabled, @DefaultValue("32") int holdBackChars,
                            @DefaultValue("true") boolean autoWindow,
                            @DefaultValue("200") int maxAutoWindow) {

        public Streaming {
            holdBackChars = Math.max(1, holdBackChars);
            maxAutoWindow = Math.max(holdBackChars, maxAutoWindow);
        }
    }
}