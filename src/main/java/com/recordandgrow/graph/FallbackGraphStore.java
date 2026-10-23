package com.recordandgrow.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 外部图谱存储的降级包装：Neo4j / LightRAG 失败时回落到 Postgres。
 *
 * <p>只兜 {@link RuntimeException}：HTTP 超时、连接拒绝、协议错误都算；回落后行为与默认实现一致。
 * 如果回落也失败，异常继续抛给 {@link GraphRetriever}，由它标成 degraded。
 */
public class FallbackGraphStore implements GraphStore {

    private static final Logger log = LoggerFactory.getLogger(FallbackGraphStore.class);

    private final GraphStore primary;
    private final GraphStore fallback;
    private final String primaryName;

    public FallbackGraphStore(GraphStore primary, GraphStore fallback, String primaryName) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryName = primaryName;
    }

    @Override
    public void replaceDocument(Long documentId, List<GraphTriple> triples) {
        try {
            primary.replaceDocument(documentId, triples);
        } catch (RuntimeException e) {
            warn("replace", e);
            fallback.replaceDocument(documentId, triples);
        }
    }

    @Override
    public void replaceDocument(Long documentId, String title, List<GraphTriple> triples) {
        try {
            primary.replaceDocument(documentId, title, triples);
        } catch (RuntimeException e) {
            warn("replace", e);
            fallback.replaceDocument(documentId, title, triples);
        }
    }

    @Override
    public List<GraphHit> search(List<String> terms, int limit) {
        try {
            return primary.search(terms, limit);
        } catch (RuntimeException e) {
            warn("search", e);
            return fallback.search(terms, limit);
        }
    }

    @Override
    public List<GraphHit> search(String question, List<String> terms, int limit) {
        try {
            return primary.search(question, terms, limit);
        } catch (RuntimeException e) {
            warn("search", e);
            return fallback.search(question, terms, limit);
        }
    }

    @Override
    public void deleteDocument(Long documentId) {
        try {
            primary.deleteDocument(documentId);
        } catch (RuntimeException e) {
            warn("delete", e);
            fallback.deleteDocument(documentId);
        }
    }

    @Override
    public long countNodes() {
        try {
            return primary.countNodes();
        } catch (RuntimeException e) {
            warn("countNodes", e);
            return fallback.countNodes();
        }
    }

    @Override
    public long countEdges() {
        try {
            return primary.countEdges();
        } catch (RuntimeException e) {
            warn("countEdges", e);
            return fallback.countEdges();
        }
    }

    private void warn(String action, RuntimeException e) {
        log.warn("graph store {} {} failed, fallback to postgres: {}",
                primaryName, action, e.getMessage());
    }
}
