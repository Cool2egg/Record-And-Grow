package com.recordandgrow.config;

import com.recordandgrow.ai.rerank.DashScopeRerankClient;
import com.recordandgrow.ai.rerank.RerankClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 精排客户端装配。关闭时不建 Bean，融合层用 {@code ObjectProvider} 取，取不到就跳过。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recordandgrow.rerank.enabled", havingValue = "true")
public class RerankConfig {

    @Bean
    public RerankClient rerankClient(RerankProperties properties) {
        return new DashScopeRerankClient(properties, new ObjectMapper());
    }
}
