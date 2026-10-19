package com.recordandgrow.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 接入配置。Key 只从环境变量读取，默认空串；应用启动不因缺 key 失败。
 */
@ConfigurationProperties(prefix = "recordandgrow.ai")
public class AiProperties {

    private final Chat chat = new Chat();
    private final Embedding embedding = new Embedding();

    public Chat getChat() {
        return chat;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public static class Chat {

        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-flash";
        private String fallbackModel = "deepseek-v4-pro";
        private boolean fallbackEnabled = true;
        private String thinking = "disabled";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration idleTimeout = Duration.ofSeconds(30);
        private Duration firstTokenTimeout = Duration.ofSeconds(60);
        private Duration totalTimeout = Duration.ofSeconds(180);
        private int maxAttempts = 2;
        private int defaultMaxTokens = 2048;

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

        public String getFallbackModel() {
            return fallbackModel;
        }

        public void setFallbackModel(String fallbackModel) {
            this.fallbackModel = fallbackModel;
        }

        public boolean isFallbackEnabled() {
            return fallbackEnabled;
        }

        public void setFallbackEnabled(boolean fallbackEnabled) {
            this.fallbackEnabled = fallbackEnabled;
        }

        public String getThinking() {
            return thinking;
        }

        public void setThinking(String thinking) {
            this.thinking = thinking;
        }

        public boolean thinkingEnabled() {
            return "enabled".equalsIgnoreCase(thinking);
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Duration getFirstTokenTimeout() {
            return firstTokenTimeout;
        }

        public void setFirstTokenTimeout(Duration firstTokenTimeout) {
            this.firstTokenTimeout = firstTokenTimeout;
        }

        public Duration getTotalTimeout() {
            return totalTimeout;
        }

        public void setTotalTimeout(Duration totalTimeout) {
            this.totalTimeout = totalTimeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getDefaultMaxTokens() {
            return defaultMaxTokens;
        }

        public void setDefaultMaxTokens(int defaultMaxTokens) {
            this.defaultMaxTokens = defaultMaxTokens;
        }
    }

    public static class Embedding {

        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        private String model = "text-embedding-v4";
        private int dimensions = 1024;
        private int batchSize = 10;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration timeout = Duration.ofSeconds(30);
        private int maxAttempts = 2;

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

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
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

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
