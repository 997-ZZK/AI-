package com.aiflow.engine.provider;

/**
 * 端点探活。实现方负责把结果写回自己的 {@link EndpointHealth}；返回值只用于日志与调度统计。
 */
public interface HealthProbe {

    String id();

    boolean probe();
}