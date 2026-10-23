package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 精排（Rerank）配置。
 *
 * <p>默认关闭：关闭时不创建客户端 Bean，融合结果保持 RRF 顺序。
 * 打开后即使调用失败也只保留原序，不会让整条文档检索失败。
 */
@ConfigurationProperties(prefix = "recordandgrow.rerank")
public class RerankProperties {

    private boolean enabled = false;

    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    /** 与 embedding 共用百炼 key 即可。 */
    private String apiKey = "";

    private String model = "gte-rerank-v2";

    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 精排是辅助路，不能比主链路还慢。 */
    private Duration timeout = Duration.ofMillis(1500);

    /**
     * 证据闸门阈值：整批最高精排分低于它，就当"这批证据都不可信"，整批丢弃。
     *
     * <p>0.2 是 gte-rerank 的经验起点，**换模型或换语料必须重新校准**。
     */
    private double minScore = 0.2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }
}
