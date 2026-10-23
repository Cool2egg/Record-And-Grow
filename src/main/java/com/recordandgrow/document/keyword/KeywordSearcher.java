package com.recordandgrow.document.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.recordandgrow.config.KeywordProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 关键词索引的读取侧。
 *
 * <p>查询只有一句 {@code match(content)}，走 BM25。**故意不做 multi_match、不做 boost**：
 * 现在只求"能召回到"，排序交给融合后的 rerank，关键词通道内部调得再好也解释不了。
 *
 * <p>失败抛 {@link KeywordStoreException}，由调用方（融合层）映射成降级原因；
 * 这里不吞异常，否则"没命中"与"ES 挂了"在指标上分不开。
 */
@Component
@ConditionalOnProperty(name = "recordandgrow.keyword.enabled", havingValue = "true")
public class KeywordSearcher {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearcher.class);

    private final ElasticsearchClient client;
    private final KeywordProperties properties;

    public KeywordSearcher(ElasticsearchClient client, KeywordProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public List<KeywordHit> search(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        try {
            SearchResponse<KeywordDoc> response = client.search(
                    KeywordRequests.search(properties.getIndexName(), question.trim(), properties.getTopK()),
                    KeywordDoc.class);
            List<KeywordHit> hits = response.hits().hits().stream()
                    .filter(hit -> hit.source() != null)
                    .map(hit -> new KeywordHit(hit.source(), hit.score() == null ? 0.0 : hit.score()))
                    .toList();
            log.debug("关键词召回: hits={}, topK={}", hits.size(), properties.getTopK());
            return hits;
        } catch (Exception e) {
            throw new KeywordStoreException("search", e);
        }
    }
}
