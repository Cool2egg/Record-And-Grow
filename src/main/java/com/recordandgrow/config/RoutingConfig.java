package com.recordandgrow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 路由模块的 Bean 装配。
 *
 * <p>分类调用单独一个小线程池（2 个 daemon 线程），不复用检索池：
 * 分类是"可以放弃"的调用，检索是"必须拿到"的调用，混在一起会互相抢占。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoutingProperties.class)
public class RoutingConfig {

    static final int ROUTING_POOL_SIZE = 2;

    @Bean(name = "routingExecutor", destroyMethod = "shutdown")
    public ExecutorService routingExecutor() {
        ThreadFactory factory = Thread.ofPlatform()
                .name("routing-", 0)
                .daemon(true)
                .factory();
        return Executors.newFixedThreadPool(ROUTING_POOL_SIZE, factory);
    }
}
