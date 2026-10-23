package com.recordandgrow.document.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.recordandgrow.config.KeywordProperties;
import com.recordandgrow.document.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 关键词索引的写入侧：建索引、批量写块、按文档删除。
 *
 * <p>**全部 fail-soft**：返回 {@code false} 并打 WARN，不向上抛。理由是关键词通道是辅助路，
 * 它挂了不应该让文档入库失败——入库失败的代价是用户重传一次文件，比少一路召回贵得多。
 *
 * <p>失败不是静默的：调用方拿返回值可以记指标，日志里带 {@code documentId} 与条数，
 * 便于后续用 reindex 补偿。真正的强一致要靠 outbox，这里不做（单机、可重跑）。
 */
@Component
@ConditionalOnProperty(name = "recordandgrow.keyword.enabled", havingValue = "true")
public class KeywordIndexer {

    private static final Logger log = LoggerFactory.getLogger(KeywordIndexer.class);

    private final ElasticsearchClient client;
    private final KeywordProperties properties;
    private final String indexTemplate;

    public KeywordIndexer(ElasticsearchClient client, KeywordProperties properties) {
        this.client = client;
        this.properties = properties;
        this.indexTemplate = loadTemplate(properties);
    }

    /**
     * 启动时确保索引存在。失败只记日志：ES 没起来时应用仍应能启动，
     * 关键词通道在检索侧自行降级。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexOnStartup() {
        try {
            ensureIndex();
        } catch (RuntimeException e) {
            log.error("关键词索引初始化失败，关键词通道将不可用: index={}, error={}",
                    properties.getIndexName(), e.toString());
        }
    }

    public void ensureIndex() {
        String index = properties.getIndexName();
        boolean exists;
        try {
            exists = client.indices().exists(e -> e.index(index)).value();
        } catch (Exception e) {
            throw new KeywordStoreException("exists", e);
        }
        if (exists) {
            return;
        }
        try {
            CreateIndexRequest request = CreateIndexRequest.of(b -> b
                    .index(index)
                    .withJson(new StringReader(indexTemplate)));
            client.indices().create(request);
            log.info("关键词索引已创建: index={}, analyzer={}", index, properties.getAnalyzer());
        } catch (Exception e) {
            // 并发启动时另一个实例可能刚建好，这类"已存在"不算失败
            if (e.getMessage() != null && e.getMessage().contains("resource_already_exists_exception")) {
                return;
            }
            throw new KeywordStoreException("ensureIndex", e);
        }
    }

    /**
     * 一批块写进 ES。{@code _id = chunkId}，重复消费时覆盖同一条，天然幂等。
     *
     * @return 是否全部写入成功
     */
    public boolean indexBatch(String title, List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return true;
        }
        try {
            BulkRequest.Builder builder = new BulkRequest.Builder();
            for (DocumentChunk chunk : chunks) {
                KeywordDoc doc = toDoc(title, chunk);
                builder.operations(op -> op.index(i -> i
                        .index(properties.getIndexName())
                        .id(String.valueOf(doc.chunkId()))
                        .document(doc)));
            }
            BulkResponse response = client.bulk(builder.build());
            if (response.errors()) {
                long failed = response.items().stream().filter(i -> i.error() != null).count();
                log.warn("关键词批量写入部分失败: documentId={}, failed={}, total={}",
                        chunks.get(0).getDocumentId(), failed, chunks.size());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("关键词批量写入失败: documentId={}, chunks={}, error={}",
                    chunks.get(0).getDocumentId(), chunks.size(), e.toString());
            return false;
        }
    }

    /**
     * 文档删除/重建时清掉它的全部块。
     *
     * @return 是否成功
     */
    public boolean deleteByDocument(long documentId) {
        try {
            client.deleteByQuery(KeywordRequests.deleteByDocument(properties.getIndexName(), documentId));
            return true;
        } catch (Exception e) {
            log.warn("关键词删除失败: documentId={}, error={}", documentId, e.toString());
            return false;
        }
    }

    static KeywordDoc toDoc(String title, DocumentChunk chunk) {
        return new KeywordDoc(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex(),
                title,
                chunk.getHeading(),
                chunk.getContent());
    }

    private static String loadTemplate(KeywordProperties properties) {
        try (InputStream in = new ClassPathResource("es/chunk-index.json").getInputStream()) {
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return template
                    .replace("${analyzer}", properties.getAnalyzer())
                    .replace("${searchAnalyzer}", properties.getSearchAnalyzer());
        } catch (IOException e) {
            throw new IllegalStateException("读取 es/chunk-index.json 失败", e);
        }
    }
}
