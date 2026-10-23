package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 知识图谱配置。
 *
 * <p>默认开启、默认 Postgres 存储：没有 READY 文档或没有 key 时图是空的，检索返回空结果，不报错。
 * 需要 Neo4j / LightRAG 时把 {@code store} 切过去；外部存储不可用会自动回落到 Postgres。
 */
@ConfigurationProperties(prefix = "recordandgrow.graph")
public class GraphProperties {

    private boolean enabled = true;

    /** 存储实现：postgres / neo4j / lightrag。未知值按 postgres 处理。 */
    private String store = "postgres";

    /** 单次检索返回的三元组上限。 */
    private int searchLimit = 5;

    /** 单篇文档最多抽多少条三元组。 */
    private int maxTriplesPerDocument = 40;

    /** 送进抽取 Prompt 的正文上限。 */
    private int extractionMaxChars = 6000;

    /** 抽取调用超时（秒级小任务）。 */
    private Duration extractionTimeout = Duration.ofSeconds(30);

    /** 一次 reindex 最多处理多少篇 READY 文档。 */
    private int reindexBatch = 10;

    private final Neo4j neo4j = new Neo4j();
    private final LightRag lightRag = new LightRag();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }

    public int getMaxTriplesPerDocument() {
        return maxTriplesPerDocument;
    }

    public void setMaxTriplesPerDocument(int maxTriplesPerDocument) {
        this.maxTriplesPerDocument = maxTriplesPerDocument;
    }

    public int getExtractionMaxChars() {
        return extractionMaxChars;
    }

    public void setExtractionMaxChars(int extractionMaxChars) {
        this.extractionMaxChars = extractionMaxChars;
    }

    public Duration getExtractionTimeout() {
        return extractionTimeout;
    }

    public void setExtractionTimeout(Duration extractionTimeout) {
        this.extractionTimeout = extractionTimeout;
    }

    public int getReindexBatch() {
        return reindexBatch;
    }

    public void setReindexBatch(int reindexBatch) {
        this.reindexBatch = reindexBatch;
    }

    public Neo4j getNeo4j() {
        return neo4j;
    }

    public LightRag getLightRag() {
        return lightRag;
    }

    /**
     * Neo4j HTTP 事务端点配置。不引 Neo4j Java Driver：只有一个 replace + 一个 search，
     * HTTP 的 {@code /db/{database}/tx/commit} 足够了，也少一个重依赖。
     */
    public static class Neo4j {

        private String url = "http://localhost:7474";
        private String database = "neo4j";
        private String username = "neo4j";
        private String password = "";
        private Duration timeout = Duration.ofSeconds(3);

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * LightRAG Server 配置。默认路径对齐 LightRAG 的 HTTP API；不同版本可改路径。
     */
    public static class LightRag {

        private String url = "http://localhost:9621";
        private String queryPath = "/query";
        /** LightRAG Server 的文本入库接口；不同版本可能是 /insert，可用环境变量覆盖。 */
        private String insertPath = "/documents/text";
        private String mode = "hybrid";
        /** 可选：LightRAG Server 开了 LIGHTRAG_API_KEY 时填同一个值。 */
        private String apiKey = "";
        private Duration timeout = Duration.ofSeconds(5);
        private int maxResponseChars = 800;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getQueryPath() {
            return queryPath;
        }

        public void setQueryPath(String queryPath) {
            this.queryPath = queryPath;
        }

        public String getInsertPath() {
            return insertPath;
        }

        public void setInsertPath(String insertPath) {
            this.insertPath = insertPath;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxResponseChars() {
            return maxResponseChars;
        }

        public void setMaxResponseChars(int maxResponseChars) {
            this.maxResponseChars = maxResponseChars;
        }
    }
}
