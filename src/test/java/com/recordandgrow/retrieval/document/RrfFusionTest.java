package com.recordandgrow.retrieval.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RRF 的算术契约：只看名次、两路都靠前的排最前、单路结果不被打乱。
 */
class RrfFusionTest {

    private static DocumentHit hit(long chunkId, double score) {
        return new DocumentHit(chunkId, chunkId, "t", null, "c", score, null, null, 0);
    }

    @Test
    void chunkRankedHighInBothChannelsWins() {
        List<DocumentHit> vector = List.of(hit(1, 0.9), hit(2, 0.8));
        List<DocumentHit> keyword = List.of(hit(2, 12.0), hit(3, 8.0));

        List<DocumentHit> fused = RrfFusion.fuse(List.of(vector, keyword), 20);

        // 2 号两路都出现：1/22 + 1/21 最大
        assertEquals(2L, fused.get(0).chunkId());
        assertEquals(1L, fused.get(1).chunkId());
        assertEquals(3L, fused.get(2).chunkId());
        assertEquals(3, fused.size(), "去重后是并集");
    }

    @Test
    void singleChannelKeepsOriginalOrder() {
        List<DocumentHit> only = List.of(hit(5, 0.7), hit(6, 0.6), hit(7, 0.5));

        List<DocumentHit> fused = RrfFusion.fuse(List.of(only, List.of()), 20);

        assertEquals(List.of(5L, 6L, 7L), fused.stream().map(DocumentHit::chunkId).toList());
    }

    @Test
    void smallerKShrinksTheAdvantageOfBeingSecond() {
        // k=1 时 1/2 与 1/3 差距 33%；k=60 时差距只有 1.6%
        double gapSmall = 1.0 / 2 - 1.0 / 3;
        double gapLarge = 1.0 / 61 - 1.0 / 62;
        List<DocumentHit> fused = RrfFusion.fuse(List.of(List.of(hit(1, 1.0), hit(2, 0.9))), 1);

        assertEquals(1L, fused.get(0).chunkId());
        assertEquals(gapSmall, fused.get(0).score() - fused.get(1).score(), 1e-9);
        org.junit.jupiter.api.Assertions.assertTrue(gapSmall > gapLarge);
    }

    @Test
    void ignoresNullChunkIdsAndEmptyChannels() {
        List<DocumentHit> withNull = List.of(hit(1, 0.5), new DocumentHit(null, 1L, "t", null, "c", 0.4, null, null, 0));

        List<DocumentHit> fused = RrfFusion.fuse(java.util.Arrays.asList(withNull, null, List.of()), 20);

        assertEquals(1, fused.size());
    }
}
