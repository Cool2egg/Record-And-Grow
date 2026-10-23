package com.recordandgrow.document.keyword;

/**
 * 一次关键词命中的结果。
 *
 * <p>{@code score} 是 BM25 分，**与向量余弦不可比**，只能用来排序；跨通道融合必须按名次算
 * （RRF），不能把两个分数量纲混在一起加。
 */
public record KeywordHit(KeywordDoc doc, double score) {

    public String field() {
        return doc == null ? null : doc.content();
    }
}
