package com.recordandgrow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 检索并行召回的线程池。
 *
 * <p>固定 4 个线程：一次提问占 2 个（时间线 + 文档），留一倍余量给并发请求；
 * 队列无界，单用户场景下不会堆积。线程为 daemon，测试里 JVM 能正常退出。
 *
 * <p>通道超时**不在这里做**——超时要先定义"超时算什么错误"，属于模块九融合层的语义，
 * 现在加上会让失败路径多出一种没有错误码的状态。
 */
@Configuration(proxyBeanMethods = false)
public class RetrievalConfig {

    static final int RETRIEVAL_POOL_SIZE = 4;

    @Bean(name = "retrievalExecutor", destroyMethod = "shutdown")
    public ExecutorService retrievalExecutor() {
        ThreadFactory factory = Thread.ofPlatform()
                .name("retrieval-", 0)
                .daemon(true)
                .factory();
        return Executors.newFixedThreadPool(RETRIEVAL_POOL_SIZE, factory);
    }
}
