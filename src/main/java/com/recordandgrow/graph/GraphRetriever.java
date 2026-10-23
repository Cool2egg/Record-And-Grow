package com.recordandgrow.graph;

import com.recordandgrow.config.GraphProperties;
import com.recordandgrow.retrieval.query.KeywordExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图谱检索：问题 → 实体名关键词 → 1 跳三元组。
 *
 * <p>没有分词器，中文用「连续汉字串的 2 字滑窗」+ 引号/英文词兜底。这会带来噪声，
 * 但图谱只作为补充证据，命中不了就返回空，不会污染主链路。
 */
@Component
public class GraphRetriever {

    private static final Logger log = LoggerFactory.getLogger(GraphRetriever.class);

    private static final Pattern CJK_RUN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final int MAX_TERMS = 8;

    private final GraphStore store;
    private final GraphProperties properties;
    private final KeywordExtractor keywordExtractor;

    public GraphRetriever(@Qualifier("graphStore") GraphStore store,
                          GraphProperties properties,
                          KeywordExtractor keywordExtractor) {
        this.store = store;
        this.properties = properties;
        this.keywordExtractor = keywordExtractor;
    }

    public GraphRetrievalResult retrieve(String question) {
        if (!properties.isEnabled()) {
            return GraphRetrievalResult.notSearched();
        }
        List<String> terms = terms(question, keywordExtractor);
        if (terms.isEmpty() && (question == null || question.isBlank())) {
            return GraphRetrievalResult.searched(List.of());
        }
        try {
            return GraphRetrievalResult.searched(
                    store.search(question, terms, Math.max(1, properties.getSearchLimit())));
        } catch (RuntimeException e) {
            log.warn("graph search failed: type={}", e.getClass().getSimpleName());
            return GraphRetrievalResult.degraded("store-error");
        }
    }

    /**
     * 没有引入 HanLP/jieba：图检索是辅助通道，单用户规模下多一个词典/进程的收益
     * 抵不过依赖与启动成本；这里用「引号/ASCII 词 + 连续汉字 2 字滑窗」做无词典召回。
     *
     * <p>它只适合 PG/Neo4j 的实体名 LIKE；LightRAG 模式必须把原问题传下去（见
     * {@link GraphStore#search(String, List, int)}），否则会把滑窗碎片当 query。
     */
    static List<String> terms(String question, KeywordExtractor extractor) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String keyword : extractor.extract(question)) {
            add(terms, keyword);
        }
        Matcher matcher = CJK_RUN.matcher(question);
        while (matcher.find() && terms.size() < MAX_TERMS) {
            String run = matcher.group();
            for (int i = 0; i + 2 <= run.length() && terms.size() < MAX_TERMS; i++) {
                add(terms, run.substring(i, i + 2));
            }
        }
        return new ArrayList<>(terms);
    }

    private static void add(Set<String> terms, String value) {
        if (terms.size() >= MAX_TERMS || value == null) {
            return;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() >= 2) {
            terms.add(normalized);
        }
    }
}
