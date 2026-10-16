package com.aiflow.engine.budget;

import java.time.Duration;

/**
 * 计数存储：只做「按 key 累加 / 读取」这一件事，方便单机内存与多实例 Redis 互换。
 *
 * <p>刻意不暴露「重置」：重置靠 TTL（自然日结束自动过期），不靠谁记得去调一次清理。
 * 依赖人工清理的计数器，最后都会在某次发布后被忘掉。
 */
public interface TokenUsageStore {

    /** 累加并返回累加后的值；key 不存在时按 delta 创建，并设置 TTL。 */
    long addAndGet(String key, long delta, Duration ttl);

    /** 读取当前值；已过期或不存在返回 0。 */
    long get(String key);

    /** 存储类型（memory / redis），用于状态接口如实展示计数口径。 */
    String kind();
}