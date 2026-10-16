package com.aiflow.engine.memory.longterm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** key 归一化：它决定「同一件事」在库里的落点，错一次就会留下两条并行的事实。 */
class MemoryKeysTest {

    @Test
    void stripsSubjectPrefixesAndFoldsSeparators() {
        assertThat(MemoryKeys.normalize("用户 公司报销规则")).isEqualTo("公司报销规则");
        assertThat(MemoryKeys.normalize("我的公司报销规则")).isEqualTo("公司报销规则");
        assertThat(MemoryKeys.normalize("我 公司报销规则")).isEqualTo("公司报销规则");
        assertThat(MemoryKeys.normalize("My   Reply-Length")).isEqualTo("reply-length");
        assertThat(MemoryKeys.normalize("回答长度偏好：三行以内")).isEqualTo("回答长度偏好-三行以内");
        assertThat(MemoryKeys.normalize("公司 报销规则"))
                .as("中文 key 里的空白是模型带来的噪声，不是分隔符").isEqualTo(MemoryKeys.normalize("公司报销规则"));
    }

    @Test
    void keepsWordsThatOnlyLookLikePrefixes() {
        // 「我们」不是「我」的另一种写法：剥掉会得到「们公司…」，那是另一个 key
        assertThat(MemoryKeys.normalize("我们公司的报销规则")).isEqualTo("我们公司的报销规则");
        assertThat(MemoryKeys.normalize("mythology")).isEqualTo("mythology");
    }

    @Test
    void returnsEmptyWhenNothingUsableIsLeft() {
        assertThat(MemoryKeys.normalize(null)).isEmpty();
        assertThat(MemoryKeys.normalize("   ")).isEmpty();
        assertThat(MemoryKeys.normalize("我的")).isEmpty();
        assertThat(MemoryKeys.normalize("！！！")).isEmpty();
    }

    @Test
    void capsKeyLengthAtTheColumnWidth() {
        String normalized = MemoryKeys.normalize("a".repeat(300));
        assertThat(normalized).hasSize(MemoryKeys.MAX_KEY_CHARS);
    }
}
