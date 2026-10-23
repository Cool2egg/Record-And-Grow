package com.recordandgrow.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 关键词通道的 ES 客户端装配。
 *
 * <p>只在 {@code recordandgrow.keyword.enabled=true} 时生效：关闭时容器里没有
 * {@link ElasticsearchClient}，依赖它的 Bean 用 {@code ObjectProvider} 取，取不到就跳过。
 *
 * <p>本地单节点、关闭安全认证，所以没有用户名密码分支——需要认证时再补，
 * 现在加只会给启动路径多一条没人测的分支。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeywordProperties.class)
@ConditionalOnProperty(name = "recordandgrow.keyword.enabled", havingValue = "true")
public class KeywordConfig {

    @Bean(destroyMethod = "close")
    public RestClient keywordRestClient(KeywordProperties properties) {
        return RestClient.builder(HttpHost.create(properties.getUrl()))
                .setRequestConfigCallback(builder -> builder
                        .setConnectTimeout((int) properties.getConnectTimeout().toMillis())
                        .setConnectionRequestTimeout((int) properties.getConnectTimeout().toMillis())
                        .setSocketTimeout((int) properties.getTimeout().toMillis()))
                .build();
    }

    @Bean
    public ElasticsearchTransport keywordTransport(RestClient keywordRestClient) {
        return new RestClientTransport(keywordRestClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient keywordClient(ElasticsearchTransport keywordTransport) {
        return new ElasticsearchClient(keywordTransport);
    }
}
