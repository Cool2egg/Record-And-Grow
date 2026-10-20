package com.recordandgrow.retrieval.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordExtractorTest {

    private final KeywordExtractor extractor = new KeywordExtractor();

    @Test
    void extractsQuotedPhraseWithoutSplittingAsciiSubwords() {
        assertThat(extractor.extract("我最近学了 \"Spring Boot\" 吗"))
                .containsExactly("Spring Boot");
    }

    @Test
    void extractsQuotedChinesePhrase() {
        assertThat(extractor.extract("关于“跑步”的记录"))
                .containsExactly("跑步");
    }

    @Test
    void extractsAsciiWords() {
        assertThat(extractor.extract("Go 和 Google 有什么区别"))
                .containsExactly("Go", "Google");
    }

    @Test
    void ignoresStopwords() {
        assertThat(extractor.extract("what did i do last week")).isEmpty();
    }

    @Test
    void chineseFreeTextProducesNoKeywords() {
        assertThat(extractor.extract("我最近在忙什么")).isEmpty();
    }

    @Test
    void deduplicatesCaseInsensitively() {
        assertThat(extractor.extract("Java java JAVA")).containsExactly("Java");
    }

    @Test
    void deduplicationIsOrderIndependentAtTokenLevel() {
        assertThat(extractor.extract("Go 和 Google")).containsExactly("Go", "Google");
        assertThat(extractor.extract("Google 和 Go")).containsExactly("Google", "Go");
    }

    @Test
    void capsKeywordCount() {
        List<String> keywords = extractor.extract("a1 b2 c3 d4 e5 f6 g7");

        assertThat(keywords).hasSize(5);
    }
}
