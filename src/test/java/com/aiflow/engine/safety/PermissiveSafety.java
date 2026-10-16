package com.aiflow.engine.safety;

import com.aiflow.engine.config.SafetyProperties;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * 测试用的空审核器：不放关键词、不放正则、保留长度上限。
 *
 * <p>内容安全的规则用例集中在 {@code ContentSafetyTest}，装配用例在 {@code SafetyIntegrationTest}；
 * 其它测试只关心自己的主题时用这个，免得一份词表把无关用例染上颜色。
 */
public final class PermissiveSafety {

    private PermissiveSafety() {
    }

    public static ContentSafety create() {
        return new ContentSafety(SafetyProperties.defaults(), new DefaultResourceLoader());
    }
}