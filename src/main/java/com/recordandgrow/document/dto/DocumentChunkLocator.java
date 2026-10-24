package com.recordandgrow.document.dto;

/**
 * 引用定位结果（模块十三 §5.5）。
 *
 * <p>前端拿着 {@code chunkId} 点引用时，不必逐页扫描：这里直接返回块本身、
 * 它所在的分页（按 {@code chunkIndex} 升序切页）以及该文档的总块数。
 *
 * @param chunk 目标块
 * @param page  目标块所在页（从 1 开始，配合 {@code /chunks?page&size} 使用）
 * @param size  计算分页时采用的每页大小，与请求参数一致
 * @param total 文档总块数
 */
public record DocumentChunkLocator(DocumentChunkResponse chunk, long page, long size, long total) {
}
