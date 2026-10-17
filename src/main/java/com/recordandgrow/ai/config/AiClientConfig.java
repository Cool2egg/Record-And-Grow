package com.recordandgrow.ai.config;

import com.recordandgrow.ai.AiCallObserver;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.EmbeddingClient;
import com.recordandgrow.ai.NoopAiCallObserver;
import com.recordandgrow.ai.deepseek.DeepSeekChatClient;
import com.recordandgrow.ai.qwen.QwenEmbeddingClient;
import com.recordandgrow.ai.support.FailoverChatClient;
import com.recordandgrow.ai.support.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/**
 * AI Bean 装配。业务层只拿到 ChatClient / EmbeddingClient 接口。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
public class AiClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AiClientConfig.class);

    /** 只用于 AI 线格式，不参与 MVC 的 JSON 配置。 */
    private final ObjectMapper mapper = new ObjectMapper();

    @Bean
    public AiCallObserver aiCallObserver() {
        return new NoopAiCallObserver();
    }

    @Bean
    public ChatClient chatClient(AiProperties properties, AiCallObserver observer) {
        AiProperties.Chat chat = properties.getChat();
        if (chat.getApiKey() == null || chat.getApiKey().isBlank()) {
            log.warn("DEEPSEEK_API_KEY 未配置，AI 对话接口调用时会返回 503");
        }
        ChatClient primary = new DeepSeekChatClient(chat, chat.getModel(), mapper, observer);
        if (!chat.isFallbackEnabled()) {
            return primary;
        }
        ChatClient fallback = new DeepSeekChatClient(chat, chat.getFallbackModel(), mapper, observer);
        RetryPolicy policy = new RetryPolicy(chat.getMaxAttempts(),
                Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(5));
        return new FailoverChatClient(List.of(
                new FailoverChatClient.Delegate("primary/" + chat.getModel(), primary,
                        Math.max(1, chat.getMaxAttempts())),
                new FailoverChatClient.Delegate("fallback/" + chat.getFallbackModel(), fallback, 1)
        ), policy);
    }

    @Bean
    public EmbeddingClient embeddingClient(AiProperties properties, AiCallObserver observer) {
        AiProperties.Embedding embedding = properties.getEmbedding();
        if (embedding.getApiKey() == null || embedding.getApiKey().isBlank()) {
            log.warn("DASHSCOPE_API_KEY 未配置，Embedding 接口调用时会返回 503");
        }
        return new QwenEmbeddingClient(embedding, mapper, observer);
    }
}
