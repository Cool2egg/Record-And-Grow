package com.recordandgrow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 记忆模块 Bean 装配。
 *
 * <p>摘要与记忆提炼共用一个小池：它们都是"可以慢、不能阻塞主链路"的后台任务。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {

    @Bean(name = "memoryExecutor", destroyMethod = "shutdown")
    public ExecutorService memoryExecutor() {
        return Executors.newFixedThreadPool(2, Thread.ofPlatform()
                .name("memory-", 0)
                .daemon(true)
                .factory());
    }
}
