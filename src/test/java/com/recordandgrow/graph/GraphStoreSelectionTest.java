package com.recordandgrow.graph;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部图谱存储的启动期验证：配置成 Neo4j 时只装配客户端，不连接、不阻塞启动；
 * 调用失败由 FallbackGraphStore 回落到 Postgres。
 */
@SpringBootTest(properties = {
        "recordandgrow.graph.store=neo4j",
        "recordandgrow.mcp.enabled=false"
})
class GraphStoreSelectionTest {

    @Autowired
    @Qualifier("graphStore")
    private GraphStore graphStore;

    @Test
    void neo4jStoreShouldBeWrappedWithFallback() {
        assertThat(graphStore).isInstanceOf(FallbackGraphStore.class);
    }
}
