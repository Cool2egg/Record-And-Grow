package com.recordandgrow.graph;

import java.util.List;

/**
 * 图谱存储抽象。默认实现是 Postgres 两张表；需要 Neo4j / LightRAG 时另写实现，
 * 领域层与 Agent 工具只依赖本接口。
 */
public interface GraphStore {

    /** 用最新抽取结果整体替换一篇文档的图（先删后插，派生数据不做增量）。 */
    void replaceDocument(Long documentId, List<GraphTriple> triples);

    /**
     * 带标题的版本。Neo4j / LightRAG 需要把出处标题写进外部存储；
     * Postgres 实现可以忽略标题（检索时 JOIN document 取）。
     */
    default void replaceDocument(Long documentId, String title, List<GraphTriple> triples) {
        replaceDocument(documentId, triples);
    }

    /** 按实体名关键词召回 1 跳三元组。term 已是小写、去空白的形式。 */
    List<GraphHit> search(List<String> terms, int limit);

    /**
     * 带原始问题的检索入口。
     *
     * <p>Postgres / Neo4j 只需要实体词，默认委托给 {@link #search(List, int)}；
     * LightRAG 需要完整问句才能做 hybrid 检索，必须覆盖本方法，不能把 2 字滑窗
     * 拼出来的碎片词当 query 发过去。
     */
    default List<GraphHit> search(String question, List<String> terms, int limit) {
        return search(terms, limit);
    }

    void deleteDocument(Long documentId);

    long countNodes();

    long countEdges();
}
