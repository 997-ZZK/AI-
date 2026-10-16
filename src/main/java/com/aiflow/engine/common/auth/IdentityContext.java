package com.aiflow.engine.common.auth;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;

/**
 * 当前请求的已认证主体（ThreadLocal）。
 *
 * <p>**必须在请求结束时清理**（见 {@link IdentityFilter} 的 finally）：Tomcat 线程是复用的，
 * 漏一次清理就是把上一个请求的身份借给下一个。
 */
public final class IdentityContext {

    private static final ThreadLocal<Subject> CURRENT = new ThreadLocal<>();

    private IdentityContext() {
    }

    public static void set(Subject subject) {
        if (subject == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(subject);
        }
    }

    public static Subject current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 自报字段 vs 令牌身份（审批的 {@code decidedBy} 与取消运行的 {@code actor} 共用这一条规则）。
     *
     * <p>只有三条，别在调用点重写：
     * <ol>
     *   <li>没有身份（鉴权关闭或匿名）-&gt; 保持原语义，自报字段照旧生效；</li>
     *   <li>有身份且没自报 -&gt; 用身份；</li>
     *   <li>有身份且自报**与身份不一致** -&gt; 400。不能默默忽略：否则调用方会以为自报生效了。</li>
     * </ol>
     */
    public static String resolveActor(String selfReported, String field) {
        Subject subject = CURRENT.get();
        String reported = selfReported == null || selfReported.isBlank() ? null : selfReported.strip();
        if (subject == null) {
            return reported;
        }
        if (reported != null && !reported.equals(subject.id())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已开启鉴权：" + field + " 由令牌身份决定（当前身份 "
                    + subject.id() + "），不要再自报");
        }
        return subject.id();
    }
}
