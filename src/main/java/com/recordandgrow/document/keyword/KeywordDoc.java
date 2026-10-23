package com.recordandgrow.document.keyword;

/**
 * 送进 ES 的一行索引文档。
 *
 * <p>{@code _id} 用 {@code chunkId}，与 pgvector 的主键对齐——两侧靠同一个 id 去重与融合，
 * 不用再维护一张映射表。
 *
 * <p>{@code content} 是块正文（与向量用的是同一段文本），{@code title / heading} 只用于展示与过滤，
 * 不参与打分。
 */
public record KeywordDoc(
        long chunkId,
        long documentId,
        int chunkIndex,
        String title,
        String heading,
        String content) {
}
