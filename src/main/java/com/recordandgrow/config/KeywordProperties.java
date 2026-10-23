package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 关键词通道（Elasticsearch）配置。
 *
 * <p>默认关闭：关闭时不创建任何 ES Bean，文档入库与问答链路与模块六完全一致。
 */
@ConfigurationProperties(prefix = "recordandgrow.keyword")
public class KeywordProperties {

    private boolean enabled = false;

    /** ES 节点地址，单节点即可。 */
    private String url = "http://localhost:9200";

    /** 索引名。换索引等于换一整套关键词数据，重建走 reindex。 */
    private String indexName = "recordandgrow-chunk";

    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 单次请求超时。关键词检索是辅助路，不能比向量路还慢。 */
    private Duration timeout = Duration.ofSeconds(3);

    /** 单次召回条数（送融合前）。 */
    private int topK = 20;

    /** 索引 analyzer：装了 IK 用 ik_max_word，没装时可降到 standard 应急。 */
    private String analyzer = "ik_max_word";

    /** 查询 analyzer：比索引侧更粗，避免把长句切得太碎。 */
    private String searchAnalyzer = "ik_smart";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
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

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public String getAnalyzer() {
        return analyzer;
    }

    public void setAnalyzer(String analyzer) {
        this.analyzer = analyzer;
    }

    public String getSearchAnalyzer() {
        return searchAnalyzer;
    }

    public void setSearchAnalyzer(String searchAnalyzer) {
        this.searchAnalyzer = searchAnalyzer;
    }
}
