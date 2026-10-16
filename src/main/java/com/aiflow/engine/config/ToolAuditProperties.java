package com.aiflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 工具调用审计配置。
 *
 * <p>内存缓冲与落库是两条腿，可以只开一个：`enabled` 管总体（内存缓冲 + 日志），
 * `persist` 只管落库。关掉落库 = 退回「此刻能查」，重启即清空。
 *
 * @param enabled               是否记录（默认开：审计的价值在于"事后能发现"，默认关等于没有）
 * @param maxEntries            内存里保留多少条（环形缓冲，超出丢最老的）
 * @param persist               是否落库留痕（跨重启追溯）；库不可用时自动回退内存缓冲并在查询结果里标注
 * @param queueSize             写库队列长度；满了丢最老的（丢多少会计数并暴露在 /tools/audit）
 * @param retentionDays         落库保留天数，<=0 表示不清理（默认 30：审计要留得比工单久一点，但不必永久）
 * @param purgeIntervalMinutes  保留期清理的间隔（默认 6 小时；由写入线程顺带执行，不引入新调度器）
 */
@ConfigurationProperties(prefix = "aiflow.tools.audit")
public record ToolAuditProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") int maxEntries,
        @DefaultValue("true") boolean persist,
        @DefaultValue("2048") int queueSize,
        @DefaultValue("30") int retentionDays,
        @DefaultValue("360") int purgeIntervalMinutes
) {

    @ConstructorBinding
    public ToolAuditProperties {
        maxEntries = Math.max(1, maxEntries);
        queueSize = Math.max(1, queueSize);
    }

    /**
     * 只要内存缓冲的简写（测试与"不落库"场景）。
     *
     * <p>record 一旦有第二个构造器，Spring 就挑不出绑定的那个 —— 必须由 {@link ConstructorBinding}
     * 指定规范构造器，否则启动时报「No default constructor found」（同 McpToolProperties 的坑）。
     */
    public ToolAuditProperties(boolean enabled, int maxEntries) {
        this(enabled, maxEntries, true, 2048, 30, 360);
    }
}
