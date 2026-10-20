package com.recordandgrow.config;

import com.recordandgrow.document.chunk.ChunkOptions;
import com.recordandgrow.document.chunk.Chunker;
import com.recordandgrow.document.chunk.JtokkitTokenCounter;
import com.recordandgrow.document.chunk.TokenCounter;
import com.recordandgrow.document.parse.DocumentParser;
import com.recordandgrow.document.storage.DocumentStorage;
import com.recordandgrow.document.storage.LocalDocumentStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文档模块的配置与 Bean 装配。
 *
 * <p>与 {@link ChatConfig} 同款：{@code @ConfigurationProperties} 类本身不加 {@code @Component}，
 * 统一由 {@code @EnableConfigurationProperties} 注册。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentConfig {

    @Bean
    public TokenCounter tokenCounter() {
        return new JtokkitTokenCounter();
    }

    @Bean
    public Chunker chunker(TokenCounter tokenCounter, DocumentProperties properties) {
        DocumentProperties.Chunk chunk = properties.getChunk();
        return new Chunker(tokenCounter, new ChunkOptions(
                chunk.getMaxTokens(), chunk.getOverlapTokens(),
                chunk.getMinTokens(), chunk.getMaxChunks()));
    }

    @Bean
    public DocumentParser documentParser(DocumentProperties properties) {
        return new DocumentParser(properties);
    }

    @Bean
    public DocumentStorage documentStorage(DocumentProperties properties) {
        return new LocalDocumentStorage(properties);
    }

    /**
     * local 队列的执行器：默认虚拟线程，上传接口立即返回。
     * 测试里覆盖成 {@code Runnable::run} 让执行变同步，断言才确定。
     */
    @Bean(name = "documentIngestExecutor", destroyMethod = "close")
    public ExecutorService documentIngestExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
