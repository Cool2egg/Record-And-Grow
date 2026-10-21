package com.recordandgrow.document.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 切块器的结构与不变式。
 *
 * <p>大部分用例注入「1 code point = 1 token」的假计数器，让块边界可精确推算；
 * 最后两条用真实的 jtokkit 计数验证 token 上限与计数一致性。
 */
class ChunkerTest {

    /** 1 code point 记 1 token，便于把注意力放在边界而不是分词上。 */
    private static final TokenCounter CODE_POINTS = new TokenCounter() {
        @Override
        public int count(String text) {
            return text == null ? 0 : text.codePointCount(0, text.length());
        }

        @Override
        public String name() {
            return "test/code-points";
        }
    };

    private final TokenCounter real = new JtokkitTokenCounter();

    @Test
    void normalizeShouldConvertCrlfAndStripTrailingSpaces() {
        String normalized = TextNormalizer.normalize("第一行  \r\n第二行\t\r\n");

        assertThat(normalized).isEqualTo("第一行\n第二行");
    }

    @Test
    void normalizeShouldCollapseBlankLinesToTwoNewlines() {
        String normalized = TextNormalizer.normalize("A\n\n\n\n\nB");

        assertThat(normalized).isEqualTo("A\n\nB");
    }

    @Test
    void normalizeShouldNotMergeInlineSpaces() {
        String normalized = TextNormalizer.normalize("a  b   c");

        assertThat(normalized).isEqualTo("a  b   c");
    }

    @Test
    void shortTextShouldProduceSingleChunk() {
        ChunkResult result = chunker(400, 60, 20, 2000).chunk("只有一句话。");

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).content()).isEqualTo("只有一句话。");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void emptyTextShouldProduceNoChunks() {
        assertThat(chunker(10, 2, 1, 10).chunk("").chunks()).isEmpty();
        assertThat(chunker(10, 2, 1, 10).chunk("   ").chunks()).isEmpty();
    }

    @Test
    void chineseWithoutSpacesShouldSplitUnderTokenLimit() {
        String text = "今天学习了自动配置原理，收获很多。".repeat(60);

        ChunkResult result = chunker(40, 10, 1, 1000).chunk(text);

        assertThat(result.chunks()).hasSizeGreaterThan(1);
        assertThat(result.chunks()).allSatisfy(chunk ->
                assertThat(CODE_POINTS.count(chunk.content())).isLessThanOrEqualTo(40));
    }

    @Test
    void contentShouldAlwaysEqualNormalizedSlice() {
        String text = "第一句话。第二句话！第三句话？第四句话；第五句话。\n\n第二段开始，内容继续。";

        List<Chunk> chunks = chunker(20, 5, 1, 100).chunk(text).chunks();

        for (Chunk chunk : chunks) {
            assertThat(chunk.content()).isEqualTo(slice(text, chunk.charStart(), chunk.charEnd()));
            assertThat(chunk.charCount()).isEqualTo(chunk.charEnd() - chunk.charStart());
            assertThat(CODE_POINTS.count(chunk.content())).isEqualTo(chunk.tokenCount());
        }
    }

    @Test
    void charStartShouldIncreaseAndOverlapShouldNotCreateGaps() {
        String text = "AAAA。BBBB。CCCC。DDDD。EEEE。FFFF。";

        List<Chunk> chunks = chunker(20, 5, 1, 100).chunk(text).chunks();

        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).charStart()).isGreaterThan(chunks.get(i - 1).charStart());
            assertThat(chunks.get(i).charStart()).isLessThanOrEqualTo(chunks.get(i - 1).charEnd());
        }
    }

    @Test
    void overlapShouldBeSentenceAlignedAndWithinBudget() {
        String text = "AAAA。BBBB。CCCC。DDDD。EEEE。FFFF。";

        List<Chunk> chunks = chunker(20, 5, 1, 100).chunk(text).chunks();

        for (int i = 1; i < chunks.size(); i++) {
            String overlap = slice(text, chunks.get(i).charStart(), chunks.get(i - 1).charEnd());
            assertThat(CODE_POINTS.count(overlap)).isLessThanOrEqualTo(5);
            // 重叠必须从上一块内部的句首开始，不能切在词中间
            assertThat(overlap).isIn("DDDD。", "BBBB。");
        }
    }

    @Test
    void longTextWithoutPunctuationShouldBeHardSplit() {
        String text = "a".repeat(1000);

        List<Chunk> chunks = chunker(100, 0, 1, 1000).chunk(text).chunks();

        assertThat(chunks).hasSize(10);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.tokenCount()).isLessThanOrEqualTo(100));
        assertThat(chunks.stream().mapToInt(Chunk::charCount).sum()).isEqualTo(1000);
    }

    @Test
    void surrogatePairsShouldNeverBeSplit() {
        String text = "😀".repeat(50);

        List<Chunk> chunks = chunker(10, 0, 1, 100).chunk(text).chunks();

        assertThat(chunks).hasSize(5);
        for (Chunk chunk : chunks) {
            assertThat(chunk.charCount()).isEqualTo(10);
            assertThat(chunk.content().codePointCount(0, chunk.content().length())).isEqualTo(10);
            assertThat(Character.isLowSurrogate(chunk.content().charAt(0))).isFalse();
        }
    }

    @Test
    void markdownHeadingsShouldBecomeBreadcrumbs() {
        String text = "# 部署\n## Docker\n容器编排说明。";

        List<Chunk> chunks = chunker(100, 10, 1, 100).chunk(text).chunks();

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).heading()).isEqualTo("部署 > Docker");
        assertThat(chunks.get(0).content()).isEqualTo("容器编排说明。");
    }

    @Test
    void headingLinesShouldNeverAppearInContent() {
        String text = "# 一\n第一段内容。\n## 二\n第二段内容。";

        List<Chunk> chunks = chunker(100, 10, 1, 100).chunk(text).chunks();

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).heading()).isEqualTo("一");
        assertThat(chunks.get(1).heading()).isEqualTo("一 > 二");
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content()).doesNotContain("#"));
    }

    @Test
    void headingShouldForceChunkBoundary() {
        String text = "AAAA。BBBB。\n# 新章节\nCCCC。DDDD。";

        List<Chunk> chunks = chunker(100, 10, 1, 100).chunk(text).chunks();

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo("AAAA。BBBB。");
        assertThat(chunks.get(1).content()).isEqualTo("CCCC。DDDD。");
        assertThat(chunks.get(1).heading()).isEqualTo("新章节");
    }

    @Test
    void smallTrailingChunkShouldBeDropped() {
        String text = "AAAA。BBBB。CC。";

        List<Chunk> chunks = chunker(10, 0, 8, 100).chunk(text).chunks();

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("AAAA。BBBB。");
    }

    @Test
    void onlyChunkShouldBeKeptEvenBelowMinTokens() {
        List<Chunk> chunks = chunker(100, 10, 20, 100).chunk("短。").chunks();

        assertThat(chunks).hasSize(1);
    }

    @Test
    void allChunksBelowMinTokensShouldNotEmptyTheResult() {
        String text = "AAAAA。BBBBB。";

        List<Chunk> chunks = chunker(10, 0, 8, 100).chunk(text).chunks();

        assertThat(chunks).hasSize(2);
    }

    @Test
    void maxChunksShouldTruncateAndFlag() {
        String text = "a".repeat(100);

        ChunkResult result = chunker(10, 0, 1, 2).chunk(text);

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.truncated()).isTrue();
        assertThat(result.chunks()).extracting(Chunk::index).containsExactly(0, 1);
    }

    @Test
    void sameInputShouldProduceIdenticalOutput() {
        String text = "今天学习了 Spring Boot 的自动配置。条件装配按需生效。接口返回统一响应体。";

        ChunkResult first = chunker(20, 5, 1, 100).chunk(text);
        ChunkResult second = chunker(20, 5, 1, 100).chunk(text);

        assertThat(first.chunks()).isEqualTo(second.chunks());
        assertThat(first.truncated()).isEqualTo(second.truncated());
    }

    @Test
    void realCounterShouldKeepEveryChunkWithinLimit() {
        String text = "今天学习了 Spring Boot 的自动配置原理，条件装配按需生效。".repeat(80);

        ChunkResult result = new Chunker(real, new ChunkOptions(400, 60, 20, 2000)).chunk(text);

        assertThat(result.chunks()).hasSizeGreaterThan(1);
        for (Chunk chunk : result.chunks()) {
            assertThat(chunk.tokenCount()).isEqualTo(real.count(chunk.content()));
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(400);
            assertThat(chunk.content()).isEqualTo(slice(text, chunk.charStart(), chunk.charEnd()));
        }
    }

    @Test
    void realCounterShouldRespectOverlapBudget() {
        String text = "这是一句用来测试重叠的中文句子。".repeat(120);

        List<Chunk> chunks = new Chunker(real, new ChunkOptions(100, 20, 1, 1000)).chunk(text).chunks();

        assertThat(chunks).hasSizeGreaterThan(2);
        for (int i = 1; i < chunks.size(); i++) {
            String overlap = slice(text, chunks.get(i).charStart(), chunks.get(i - 1).charEnd());
            assertThat(real.count(overlap)).isLessThanOrEqualTo(20);
        }
    }

    @Test
    void invalidOptionsShouldFailFast() {
        assertThatThrownBy(() -> new ChunkOptions(10, 10, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkOptions(0, 0, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Chunker chunker(int maxTokens, int overlapTokens, int minTokens, int maxChunks) {
        return new Chunker(CODE_POINTS, new ChunkOptions(maxTokens, overlapTokens, minTokens, maxChunks));
    }

    private static String slice(String text, int startCp, int endCp) {
        int start = text.offsetByCodePoints(0, startCp);
        int end = text.offsetByCodePoints(0, endCp);
        return text.substring(start, end);
    }
}
