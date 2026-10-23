package com.recordandgrow.config;

import com.recordandgrow.ai.config.AiProperties;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AgentScope 装配。
 *
 * <p>只引 core + OpenAI 兼容模型扩展，不引 Boot starter：starter 面向 Boot 3，
 * 本项目是 Boot 4，手动建 Bean 更可控。
 *
 * <p>模型直连 OpenAI 协议端点（DeepSeek 兼容），不套 {@code FailoverChatClient}：
 * AgentScope 自带 maxRetries / fallbackModel，两层重试叠在一起只会放大延迟。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

    private static final String CHAT_COMPLETIONS = "/v1/chat/completions";

    @Bean(name = "agentModel")
    public Model agentModel(AiProperties aiProperties) {
        AiProperties.Chat chat = aiProperties.getChat();
        return build(chat, chat.getModel());
    }

    /** 主模型失败时的兜底模型；未开启时 Agent 不会挂载它。 */
    @Bean(name = "agentFallbackModel")
    public Model agentFallbackModel(AiProperties aiProperties) {
        AiProperties.Chat chat = aiProperties.getChat();
        return build(chat, chat.getFallbackModel());
    }

    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private static Model build(AiProperties.Chat chat, String modelName) {
        return OpenAIChatModel.builder()
                .apiKey(chat.getApiKey())
                .baseUrl(trimTrailingSlash(chat.getBaseUrl()))
                .endpointPath(CHAT_COMPLETIONS)
                .modelName(modelName)
                .stream(true)
                .build();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.deepseek.com";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // 用户若把 base-url 配成 .../v1，端点里再拼 /v1 会重复
        if (trimmed.endsWith("/v1")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed;
    }
}
