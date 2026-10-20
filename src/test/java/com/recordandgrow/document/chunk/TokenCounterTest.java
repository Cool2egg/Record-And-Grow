package com.recordandgrow.document.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分词器与归一化的基础约定。
 */
class TokenCounterTest {

    private final TokenCounter counter = new JtokkitTokenCounter();

    @Test
    void emptyStringShouldCountZero() {
        assertThat(counter.count("")).isZero();
        assertThat(counter.count(null)).isZero();
    }

    @Test
    void sameTextShouldAlwaysCountTheSame() {
        String text = "Spring Boot 的自动配置与条件装配";
        assertThat(counter.count(text)).isEqualTo(counter.count(text)).isPositive();
    }

    @Test
    void chineseShouldCostMoreTokensPerCharacterThanAscii() {
        String chinese = "这是一段中文文本用于比较分词密度";
        String ascii = "this is a plain english sentence for comparison";
        double chinesePerChar = (double) counter.count(chinese) / chinese.length();
        double asciiPerChar = (double) counter.count(ascii) / ascii.length();

        assertThat(chinesePerChar).isGreaterThan(asciiPerChar);
    }

    @Test
    void nameShouldIdentifyImplementation() {
        assertThat(counter.name()).isEqualTo("jtokkit/cl100k_base");
    }
}
