package com.recordandgrow.graph;

/**
 * 一条图谱命中：{@code source --relation--> target}，附带出处文档。
 *
 * @param documentId 出处文档；前端可跳转
 * @param title      出处文档标题
 */
public record GraphHit(
        Long edgeId,
        String source,
        String relation,
        String target,
        Long documentId,
        String title) {
}
