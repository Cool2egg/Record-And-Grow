package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 业务对话配置：时间窗、上下文预算、回答长度、文档检索。
 *
 * <p>与 {@code recordandgrow.ai.*} 分开：AI 配置描述“怎么连模型”，
 * 这里描述“怎么检索、放多少上下文、回答多长”。
 */
@ConfigurationProperties(prefix = "recordandgrow.chat")
public class ChatProperties {

    /** 计算“今天/上周/10月”等时间表达所用的时区。 */
    private String timezone = "Asia/Shanghai";

    /** 问题里没有时间表达时的默认窗口（天）。 */
    private int defaultWindowDays = 30;

    /** 时间窗上限（天），超出时从起点夹紧。 */
    private int maxWindowDays = 365;

    /** 单次检索最多取回多少条（同时受 TimelineEntryService.MAX_PAGE_SIZE 限制）。 */
    private int maxEntries = 40;

    /** Prompt 中单条记录的字符上限（第一档）。 */
    private int maxEntryChars = 500;

    /** 仍超预算时单条记录的第二档字符上限。 */
    private int minEntryChars = 300;

    /** system + user prompt 的总字符预算。 */
    private int maxContextChars = 8000;

    /** 记录区自己的字符上限，与总预算取较小值。 */
    private int maxRecordsChars = 6000;

    /** 问题最大字符数（运行时校验）。 */
    private int maxQuestionChars = 2000;

    /** 请求未指定 maxTokens 时的默认值。 */
    private int answerMaxTokens = 1024;

    /** 请求可指定的 maxTokens 硬上限。 */
    private int maxAnswerTokens = 4096;

    /** 请求未指定 temperature 时的默认值。 */
    private double temperature = 0.3;

    /** 文档检索参数；与时间线预算共用 {@code max-context-chars}。 */
    private final Documents documents = new Documents();

    public Documents getDocuments() {
        return documents;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public int getDefaultWindowDays() {
        return defaultWindowDays;
    }

    public void setDefaultWindowDays(int defaultWindowDays) {
        this.defaultWindowDays = defaultWindowDays;
    }

    public int getMaxWindowDays() {
        return maxWindowDays;
    }

    public void setMaxWindowDays(int maxWindowDays) {
        this.maxWindowDays = maxWindowDays;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public int getMaxEntryChars() {
        return maxEntryChars;
    }

    public void setMaxEntryChars(int maxEntryChars) {
        this.maxEntryChars = maxEntryChars;
    }

    public int getMinEntryChars() {
        return minEntryChars;
    }

    public void setMinEntryChars(int minEntryChars) {
        this.minEntryChars = minEntryChars;
    }

    public int getMaxContextChars() {
        return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }

    public int getMaxRecordsChars() {
        return maxRecordsChars;
    }

    public void setMaxRecordsChars(int maxRecordsChars) {
        this.maxRecordsChars = maxRecordsChars;
    }

    public int getMaxQuestionChars() {
        return maxQuestionChars;
    }

    public void setMaxQuestionChars(int maxQuestionChars) {
        this.maxQuestionChars = maxQuestionChars;
    }

    public int getAnswerMaxTokens() {
        return answerMaxTokens;
    }

    public void setAnswerMaxTokens(int answerMaxTokens) {
        this.answerMaxTokens = answerMaxTokens;
    }

    public int getMaxAnswerTokens() {
        return maxAnswerTokens;
    }

    public void setMaxAnswerTokens(int maxAnswerTokens) {
        this.maxAnswerTokens = maxAnswerTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    /**
     * 文档检索（模块六）。所有键都有默认值：缺省配置时行为与设计稿一致。
     *
     * <p>{@code enabled=false} 等价于「不查文档」：不调 embedding、SQL 不执行，
     * 对外表现为 {@code documentSearched=false}，与「没有 READY 文档」不可区分。
     */
    public static class Documents {

        /** 文档检索总开关。 */
        private boolean enabled = true;

        /** 最终纳入候选的文档块上限。 */
        private int topK = 8;

        /** SQL {@code LIMIT}，为阈值/去重留余量，默认 3×topK。 */
        private int scanLimit = 24;

        /** 相似度阈值；{@code similarity = 1 - cosine_distance}。 */
        private double similarityThreshold = 0.25;

        /** 同一文档最多纳入几块，防单个文档霸屏。 */
        private int maxPerDocument = 2;

        /** Prompt 里单个块的字符上限，超出截断。 */
        private int maxBlockChars = 300;

        /** 文档区字符上限；仍受总预算约束。 */
        private int maxChars = 2000;

        /** 查询向量化的请求超时，按次覆盖 {@code ai.embedding.timeout}。 */
        private Duration queryTimeout = Duration.ofSeconds(8);

        /** 查询向量化的尝试次数，按次覆盖 {@code ai.embedding.max-attempts}。 */
        private int queryMaxAttempts = 1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public int getScanLimit() {
            return scanLimit;
        }

        public void setScanLimit(int scanLimit) {
            this.scanLimit = scanLimit;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public int getMaxPerDocument() {
            return maxPerDocument;
        }

        public void setMaxPerDocument(int maxPerDocument) {
            this.maxPerDocument = maxPerDocument;
        }

        public int getMaxBlockChars() {
            return maxBlockChars;
        }

        public void setMaxBlockChars(int maxBlockChars) {
            this.maxBlockChars = maxBlockChars;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public Duration getQueryTimeout() {
            return queryTimeout;
        }

        public void setQueryTimeout(Duration queryTimeout) {
            this.queryTimeout = queryTimeout;
        }

        public int getQueryMaxAttempts() {
            return queryMaxAttempts;
        }

        public void setQueryMaxAttempts(int queryMaxAttempts) {
            this.queryMaxAttempts = queryMaxAttempts;
        }
    }
}
