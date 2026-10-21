package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

/**
 * 文档入库配置。
 *
 * <p>与 {@code recordandgrow.ai.*} 分开：那边描述「怎么连模型」，这里描述
 * 「收多大文件、切多大块、消息发到哪、对账多快扫一次」。
 */
@ConfigurationProperties(prefix = "recordandgrow.document")
public class DocumentProperties {

    /** 原文存放目录，相对工作目录。会加进 .gitignore。 */
    private String storageDir = "./data/documents";

    /** 业务侧文件大小上限；与 spring.servlet.multipart.max-file-size 配合生效。 */
    private DataSize maxFileSize = DataSize.ofMegabytes(10);

    /** 扩展名白名单，小写。MIME 仍以 Tika 嗅探为准。 */
    private List<String> allowedExtensions = List.of("pdf", "md", "markdown", "txt");

    /** 归一化全文的字符上限，超出直接判失败。 */
    private int maxChars = 2_000_000;

    /** 单次 Tika 解析的超时，超时取消并判失败。 */
    private Duration parseTimeout = Duration.ofSeconds(60);

    private final Queue queue = new Queue();

    private final Chunk chunk = new Chunk();

    private final Reconcile reconcile = new Reconcile();

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = maxChars;
    }

    public Duration getParseTimeout() {
        return parseTimeout;
    }

    public void setParseTimeout(Duration parseTimeout) {
        this.parseTimeout = parseTimeout;
    }

    public Queue getQueue() {
        return queue;
    }

    public Chunk getChunk() {
        return chunk;
    }

    public Reconcile getReconcile() {
        return reconcile;
    }

    /** 消息队列配置。 */
    public static class Queue {

        /** rocketmq | local。默认 local：没有 broker 也能起应用、跑测试。 */
        private String mode = "local";

        private String namesrv = "127.0.0.1:9876";

        private String parseTopic = "doc-ingest-parse";

        private String embedTopic = "doc-ingest-embed";

        private String producerGroup = "recordandgrow-doc-producer";

        private String consumerGroupPrefix = "recordandgrow-doc";

        /** MQ 重投次数上限；第 4 次投递仍失败就判 FAILED。 */
        private int maxReconsumeTimes = 3;

        private Duration sendTimeout = Duration.ofSeconds(3);

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getNamesrv() {
            return namesrv;
        }

        public void setNamesrv(String namesrv) {
            this.namesrv = namesrv;
        }

        public String getParseTopic() {
            return parseTopic;
        }

        public void setParseTopic(String parseTopic) {
            this.parseTopic = parseTopic;
        }

        public String getEmbedTopic() {
            return embedTopic;
        }

        public void setEmbedTopic(String embedTopic) {
            this.embedTopic = embedTopic;
        }

        public String getProducerGroup() {
            return producerGroup;
        }

        public void setProducerGroup(String producerGroup) {
            this.producerGroup = producerGroup;
        }

        public String getConsumerGroupPrefix() {
            return consumerGroupPrefix;
        }

        public void setConsumerGroupPrefix(String consumerGroupPrefix) {
            this.consumerGroupPrefix = consumerGroupPrefix;
        }

        public int getMaxReconsumeTimes() {
            return maxReconsumeTimes;
        }

        public void setMaxReconsumeTimes(int maxReconsumeTimes) {
            this.maxReconsumeTimes = maxReconsumeTimes;
        }

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }
    }

    /** 切块参数，单位都是 token（jtokkit cl100k 近似值）。 */
    public static class Chunk {

        private int maxTokens = 400;

        private int overlapTokens = 60;

        private int minTokens = 20;

        private int maxChunks = 2000;

        /** 写进验证文档的分词器标识，方便以后换实现时对照。 */
        private String tokenizer = "cl100k";

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getOverlapTokens() {
            return overlapTokens;
        }

        public void setOverlapTokens(int overlapTokens) {
            this.overlapTokens = overlapTokens;
        }

        public int getMinTokens() {
            return minTokens;
        }

        public void setMinTokens(int minTokens) {
            this.minTokens = minTokens;
        }

        public int getMaxChunks() {
            return maxChunks;
        }

        public void setMaxChunks(int maxChunks) {
            this.maxChunks = maxChunks;
        }

        public String getTokenizer() {
            return tokenizer;
        }

        public void setTokenizer(String tokenizer) {
            this.tokenizer = tokenizer;
        }
    }

    /** 对账任务。 */
    public static class Reconcile {

        private boolean enabled = true;

        private Duration interval = Duration.ofMinutes(5);

        /** 心跳超过这个时长没更新才算卡住。 */
        private Duration staleAfter = Duration.ofMinutes(10);

        /** 补发次数上限，超过判 FAILED。 */
        private int maxRetries = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getStaleAfter() {
            return staleAfter;
        }

        public void setStaleAfter(Duration staleAfter) {
            this.staleAfter = staleAfter;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }
}
