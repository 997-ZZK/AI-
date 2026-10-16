package com.aiflow.engine.common.auth;

/**
 * 已认证的主体（令牌解析结果）。
 *
 * <p>{@code id} 是**唯一会被写进业务字段**的东西：审批的 {@code decidedBy}、取消运行的 {@code actor} 都用它，
 * 所以它必须是稳定标识（人名、工号、邮箱都行，别用会变的显示名）。
 */
public record Subject(String id, String displayName) {

    public Subject {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("subject id 不能为空");
        }
    }
}
