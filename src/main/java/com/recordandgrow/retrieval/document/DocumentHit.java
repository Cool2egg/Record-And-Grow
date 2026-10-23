package com.recordandgrow.retrieval.document;

/**
 * 一个命中的文档块。
 *
 * <p>{@code score} 的口径取决于来源：向量通道是 {@code 1 - cosine_distance}，
 * 关键词通道是 BM25，融合后是 RRF 分。**三种分数不可互相比大小**，只有同一列表内部可比；
 * 跨通道比较一律走名次（见 {@link RrfFusion}）。
 *
 * <p>{@code charStart / charEnd} 是归一化全文里的 code point 下标，前端要跳转到原文时用；
 * {@code content} 是块正文（不是归一化后的 Prompt 文本）。
 *
 * <p>{@code chunkIndex} 用于配额时的相邻块判定；旧调用点不知道块序号，用 8 参构造填 {@code -1}，
 * 此时不会与任何块被判为相邻（差 1 才去重）。
 */
public record DocumentHit(
        Long chunkId,
        Long documentId,
        String title,
        String heading,
        String content,
        double score,
        Integer charStart,
        Integer charEnd,
        int chunkIndex) {

    /** 兼容模块六的 8 参构造：块序号未知。 */
    public DocumentHit(Long chunkId,
                       Long documentId,
                       String title,
                       String heading,
                       String content,
                       double score,
                       Integer charStart,
                       Integer charEnd) {
        this(chunkId, documentId, title, heading, content, score, charStart, charEnd, -1);
    }
}
