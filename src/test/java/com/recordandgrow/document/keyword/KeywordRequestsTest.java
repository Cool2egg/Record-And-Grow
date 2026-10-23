package com.recordandgrow.document.keyword;

import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求构造的契约测试：断言 DSL 形状，不 mock 客户端。
 *
 * <p>这三条是后续融合的前提：索引 {@code _id} 等于 chunkId（两侧对得上）、
 * 删除按 documentId 过滤、检索只打 content 字段。
 */
class KeywordRequestsTest {

    private static final String INDEX = "recordandgrow-chunk";

    @Test
    void indexUsesChunkIdAsDocumentId() {
        KeywordDoc doc = new KeywordDoc(42L, 7L, 3, "Docker 笔记", "部署 > 镜像", "配置 registry-mirrors");

        IndexRequest request = KeywordRequests.index(INDEX, doc);

        assertEquals(INDEX, request.index());
        assertEquals("42", request.id(), "_id 必须是 chunkId，才能和 pgvector 主键对齐");
        assertEquals(doc, request.document());
    }

    @Test
    void deleteByDocumentFiltersOnDocumentIdWithRefresh() {
        DeleteByQueryRequest request = KeywordRequests.deleteByDocument(INDEX, 7L);

        assertEquals(List.of(INDEX), request.index());
        assertEquals(Boolean.TRUE, request.refresh(), "删除后要立刻查询可见");
        assertEquals("documentId", request.query().term().field());
        assertEquals(7L, request.query().term().value().longValue());
    }

    @Test
    void searchUsesSingleMatchOnContent() {
        SearchRequest request = KeywordRequests.search(INDEX, "镜像加速", 20);

        assertEquals(List.of(INDEX), request.index());
        assertEquals(20, request.size());
        assertEquals("content", request.query().match().field());
        assertEquals("镜像加速", request.query().match().query().stringValue());
    }

    @Test
    void searchSizeIsClampedToAtLeastOne() {
        assertTrue(KeywordRequests.search(INDEX, "q", 0).size() >= 1);
    }
}
