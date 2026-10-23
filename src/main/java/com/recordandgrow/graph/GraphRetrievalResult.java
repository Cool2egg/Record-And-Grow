package com.recordandgrow.graph;

import java.util.List;

/**
 * 一次图谱检索的结果，三态与文档检索保持一致：
 * 未检索（关闭）、已检索（0 条也算）、降级（存储查询失败）。
 */
public record GraphRetrievalResult(
        List<GraphHit> hits,
        boolean searched,
        boolean degraded,
        String degradeReason) {

    public GraphRetrievalResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public static GraphRetrievalResult notSearched() {
        return new GraphRetrievalResult(List.of(), false, false, null);
    }

    public static GraphRetrievalResult searched(List<GraphHit> hits) {
        return new GraphRetrievalResult(hits, true, false, null);
    }

    public static GraphRetrievalResult degraded(String reason) {
        return new GraphRetrievalResult(List.of(), false, true, reason);
    }
}
