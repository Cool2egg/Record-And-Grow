package com.recordandgrow.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.config.GraphProperties;
import com.recordandgrow.document.Document;
import com.recordandgrow.document.DocumentMapper;
import com.recordandgrow.document.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 文档 → 图谱的索引器。
 *
 * <p>触发时机：文档 READY 后异步调用，以及手工 reindex。没有 key / 抽取为空时**不清空旧图**，
 * 避免一次模型抖动把已有图删掉。
 */
@Service
public class GraphIndexer {

    private static final Logger log = LoggerFactory.getLogger(GraphIndexer.class);

    private final DocumentMapper documentMapper;
    private final GraphExtractor extractor;
    private final GraphStore store;
    private final GraphProperties properties;
    private final ChatClient chatClient;
    private final ExecutorService executor;

    public GraphIndexer(DocumentMapper documentMapper,
                        GraphExtractor extractor,
                        @Qualifier("graphStore") GraphStore store,
                        GraphProperties properties,
                        ChatClient chatClient,
                        @Qualifier("graphExecutor") ExecutorService executor) {
        this.documentMapper = documentMapper;
        this.extractor = extractor;
        this.store = store;
        this.properties = properties;
        this.chatClient = chatClient;
        this.executor = executor;
    }

    public void indexAsync(Long documentId) {
        if (!properties.isEnabled() || !chatClient.isConfigured()) {
            return;
        }
        executor.execute(() -> {
            try {
                index(documentId);
            } catch (RuntimeException e) {
                log.warn("graph index failed: documentId={}, type={}",
                        documentId, e.getClass().getSimpleName());
            }
        });
    }

    /** 同步索引一篇文档，返回是否写入。 */
    public boolean index(Long documentId) {
        if (!properties.isEnabled() || !chatClient.isConfigured()) {
            return false;
        }
        Document document = documentMapper.selectById(documentId);
        if (document == null
                || !DocumentStatus.READY.name().equals(document.getStatus())
                || document.getNormalizedText() == null
                || document.getNormalizedText().isBlank()) {
            return false;
        }
        List<GraphTriple> triples = extractor.extract(document.getTitle(), document.getNormalizedText());
        if (triples.isEmpty()) {
            return false;
        }
        store.replaceDocument(documentId, document.getTitle(), triples);
        log.info("graph indexed: documentId={}, triples={}", documentId, triples.size());
        return true;
    }

    /** 手工重建：按更新时间取一批 READY 文档，逐篇抽取。返回成功篇数。 */
    public int reindex(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, Math.max(1, properties.getReindexBatch())));
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getStatus, DocumentStatus.READY.name())
                .orderByDesc(Document::getUpdatedAt)
                .last("LIMIT " + safeLimit));
        int indexed = 0;
        for (Document document : documents) {
            try {
                if (index(document.getId())) {
                    indexed++;
                }
            } catch (RuntimeException e) {
                log.warn("graph reindex failed: documentId={}, type={}",
                        document.getId(), e.getClass().getSimpleName());
            }
        }
        return indexed;
    }

    public GraphStats stats() {
        return new GraphStats(store.countNodes(), store.countEdges(), properties.isEnabled());
    }

    /** 文档软删时显式清图：FK 的 ON DELETE CASCADE 只在物理删时生效。 */
    public void deleteDocument(Long documentId) {
        try {
            store.deleteDocument(documentId);
        } catch (RuntimeException e) {
            log.warn("graph delete failed: documentId={}, type={}",
                    documentId, e.getClass().getSimpleName());
        }
    }

    public record GraphStats(long nodes, long edges, boolean enabled) {
    }
}
