package com.recordandgrow.document.keyword;

import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;

/**
 * ES 请求构造。全是纯函数，便于单测断言 DLS 写对了没——不 mock 客户端。
 *
 * <p>刻意不用 {@code multi_match} / {@code function_score}：查询越简单，调参时越容易解释
 * 分数是怎么来的。
 */
final class KeywordRequests {

    static final String FIELD_CONTENT = "content";
    static final String FIELD_DOCUMENT_ID = "documentId";

    private KeywordRequests() {
    }

    static IndexRequest index(String index, KeywordDoc doc) {
        return IndexRequest.of(b -> b
                .index(index)
                .id(String.valueOf(doc.chunkId()))
                .document(doc));
    }

    /** 删文档时按 {@code documentId} 清掉它所有块；{@code refresh=true} 让后续查询立刻看不到。 */
    static DeleteByQueryRequest deleteByDocument(String index, long documentId) {
        return DeleteByQueryRequest.of(b -> b
                .index(index)
                .refresh(true)
                .query(q -> q.term(t -> t.field(FIELD_DOCUMENT_ID).value(documentId))));
    }

    static SearchRequest search(String index, String question, int topK) {
        return SearchRequest.of(b -> b
                .index(index)
                .size(Math.max(1, topK))
                .query(q -> q.match(m -> m.field(FIELD_CONTENT).query(question))));
    }
}
