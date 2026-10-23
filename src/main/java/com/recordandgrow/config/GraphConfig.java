package com.recordandgrow.config;

import com.recordandgrow.graph.FallbackGraphStore;
import com.recordandgrow.graph.GraphStore;
import com.recordandgrow.graph.JdbcGraphStore;
import com.recordandgrow.graph.LightRagGraphStore;
import com.recordandgrow.graph.Neo4jGraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图谱 Bean 装配。
 *
 * <p>存储选择：{@code recordandgrow.graph.store=postgres|neo4j|lightrag}。
 * 外部存储用 {@link FallbackGraphStore} 包装，调用失败自动回落 Postgres；
 * 启动阶段不连接外部存储，没有 Neo4j/LightRAG 也能正常启动。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphProperties.class)
public class GraphConfig {

    private static final Logger log = LoggerFactory.getLogger(GraphConfig.class);

    @Bean(name = "graphExecutor", destroyMethod = "shutdown")
    public ExecutorService graphExecutor() {
        return Executors.newFixedThreadPool(1, Thread.ofPlatform()
                .name("graph-", 0)
                .daemon(true)
                .factory());
    }

    @Bean(name = "graphStore")
    @Primary
    public GraphStore graphStore(GraphProperties properties, JdbcGraphStore jdbcGraphStore) {
        String store = properties.getStore() == null
                ? "postgres" : properties.getStore().trim().toLowerCase(Locale.ROOT);
        return switch (store) {
            case "neo4j" -> {
                log.info("graph store: neo4j (fallback postgres)");
                yield new FallbackGraphStore(new Neo4jGraphStore(properties), jdbcGraphStore, "neo4j");
            }
            case "lightrag", "light-rag" -> {
                log.info("graph store: lightrag (fallback postgres)");
                yield new FallbackGraphStore(new LightRagGraphStore(properties), jdbcGraphStore, "lightrag");
            }
            default -> {
                if (!"postgres".equals(store) && !store.isBlank()) {
                    log.warn("unknown graph store '{}', fallback to postgres", store);
                }
                yield jdbcGraphStore;
            }
        };
    }
}
