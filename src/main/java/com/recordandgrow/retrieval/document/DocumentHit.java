package com.recordandgrow.retrieval.document;

/**
 * 一个命中的文档块。
 *
 * <p>{@code score = 1 - cosine_distance}，保留完整 {@code double}：排序与阈值都用它，
 * 只有展示（{@code SourceItem.score}）与日志（4 位）才舍入。
 *
 * <p>{@code charStart / charEnd} 是归一化全文里的 code point 下标，前端要跳转到原文时用；
 * {@code content} 是块正文（不是归一化后的 Prompt 文本）。
 */
public record DocumentHit(
        Long chunkId,
        Long documentId,
        String title,
        String heading,
        String content,
        double score,
        Integer charStart,
        Integer charEnd) {
}
