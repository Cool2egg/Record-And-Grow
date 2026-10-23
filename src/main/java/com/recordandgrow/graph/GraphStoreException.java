package com.recordandgrow.graph;

/**
 * 外部图谱存储（Neo4j / LightRAG）调用失败。统一成运行时异常，由 FallbackGraphStore 兜底。
 */
public class GraphStoreException extends RuntimeException {

    public GraphStoreException(String message) {
        super(message);
    }

    public GraphStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
