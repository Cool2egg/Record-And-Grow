package com.recordandgrow.document;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档检索读路径的库级集成测试：真实 MyBatis + 真实 pgvector。
 *
 * <p>验的是「SQL 与投影映射的正确性」：过滤条件（READY / 软删 / embedding 非空 /
 * embedding_model）、{@code <=>} 排序、{@code LIMIT}、平局三连 tie-break。
 * 不断言执行计划是否走 HNSW——小表顺序扫描也正确，计划记录在验证文档里。
 *
 * <p>排序用明显分离的向量，不依赖 HNSW 对「分数很接近」的近似排序。
 * 依赖 Docker，沙箱跳过。
 */
@Testcontainers
@SpringBootTest
@Transactional
class DocumentRetrievalIntegrationTest {

    private static final String MODEL = "text-embedding-v4";
    private static final int DIMENSIONS = 1024;
    private static final java.util.concurrent.atomic.AtomicInteger SHA_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("recordandgrow")
            .withUsername("recordandgrow")
            .withPassword("recordandgrow");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Autowired
    private DocumentChunkMapper chunkMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void searchKeepsOnlyReadyActiveEmbeddedRowsOfCurrentModel() {
        long ready = insertDocument("READY", false);
        long deleted = insertDocumentReadyButDeleted();
        long pending = insertDocument("PENDING", false);
        long otherModel = insertDocument("READY", false);

        long readyChunk = insertChunk(ready, 0, vector(1f, 0f), MODEL);
        long deletedChunk = insertChunk(deleted, 0, vector(0.99f, 0.01f), MODEL);
        long pendingChunk = insertChunk(pending, 0, vector(0.99f, 0.01f), MODEL);
        long otherModelChunk = insertChunk(otherModel, 0, vector(0.99f, 0.01f), "other-model");
        long nullEmbedding = insertChunk(ready, 1, null, MODEL);

        List<DocumentSearchRow> rows = chunkMapper.search(vector(1f, 0f), MODEL, 20);

        assertThat(rows).extracting(DocumentSearchRow::getChunkId).containsExactly(readyChunk);
        assertThat(rows).extracting(DocumentSearchRow::getChunkId)
                .doesNotContain(deletedChunk, pendingChunk, otherModelChunk, nullEmbedding);
        assertThat(rows.get(0).getDocumentId()).isEqualTo(ready);
        assertThat(rows.get(0).getTitle()).isEqualTo("集成测试文档");
    }

    @Test
    void searchOrdersByCosineDistance() {
        long document = insertDocument("READY", false);
        String query = vector(1f, 0f);
        long far = insertChunk(document, 0, vector(0f, 1f), MODEL);
        long near = insertChunk(document, 1, vector(0.95f, 0.05f), MODEL);
        long middle = insertChunk(document, 2, vector(0.7f, 0.3f), MODEL);

        List<DocumentSearchRow> rows = chunkMapper.search(query, MODEL, 20);

        assertThat(rows).extracting(DocumentSearchRow::getChunkId).containsExactly(near, middle, far);
        assertThat(rows.get(0).getDistance()).isLessThan(rows.get(1).getDistance());
        assertThat(rows.get(1).getDistance()).isLessThan(rows.get(2).getDistance());
        // 相似度 = 1 - 距离；投影必须带回可换算的 distance
        assertThat(1 - rows.get(0).getDistance()).isGreaterThan(0.99);
    }

    @Test
    void searchHonoursScanLimit() {
        long document = insertDocument("READY", false);
        for (int i = 0; i < 10; i++) {
            insertChunk(document, i, vector(1f, i * 0.02f), MODEL);
        }

        assertThat(chunkMapper.search(vector(1f, 0f), MODEL, 3)).hasSize(3);
        assertThat(chunkMapper.search(vector(1f, 0f), MODEL, 24)).hasSize(10);
    }

    @Test
    void identicalScoresFallBackToDocumentThenChunkIndex() {
        long earlier = insertDocument("READY", false);
        long later = insertDocument("READY", false);
        long laterChunk = insertChunk(later, 1, vector(1f, 0f), MODEL);
        long earlierChunk1 = insertChunk(earlier, 1, vector(1f, 0f), MODEL);
        long earlierChunk0 = insertChunk(earlier, 0, vector(1f, 0f), MODEL);

        List<DocumentSearchRow> rows = chunkMapper.search(vector(1f, 0f), MODEL, 20);

        assertThat(rows).extracting(DocumentSearchRow::getChunkId)
                .containsExactly(earlierChunk0, earlierChunk1, laterChunk);
    }

    @Test
    void existsReadyIgnoresNonReadyAndDeleted() {
        assertThat(documentMapper.existsReady()).isFalse();

        insertDocument("PENDING", false);
        insertDocument("FAILED", false);
        assertThat(documentMapper.existsReady()).isFalse();

        insertDocumentReadyButDeleted();
        assertThat(documentMapper.existsReady()).as("软删的 READY 不算").isFalse();

        insertDocument("READY", false);
        assertThat(documentMapper.existsReady()).isTrue();
    }

    // ---- helpers ----

    private long insertDocument(String status, boolean deleted) {
        Long id = jdbc.queryForObject("""
                INSERT INTO document (title, file_name, mime_type, file_size, storage_path, content_sha256,
                                      status, deleted)
                VALUES ('集成测试文档', 'it.txt', 'text/plain', 10, './data/it.txt', ?, ?, ?)
                RETURNING id
                """, Long.class, sha(status, deleted), status, deleted);
        return id == null ? 0L : id;
    }

    /** 软删的文档必须唯一：直接写 deleted，绕开 @TableLogic。 */
    private long insertDocumentReadyButDeleted() {
        return insertDocument("READY", true);
    }

    private long insertChunk(long documentId, int index, String literal, String model) {
        Long id = jdbc.queryForObject("""
                INSERT INTO document_chunk
                    (document_id, chunk_index, content, heading, char_start, char_end, char_count, token_count,
                     embedding, embedding_model)
                VALUES (?, ?, ?, '部署 > 镜像加速', 0, 6, 6, 3, cast(? AS vector), ?)
                RETURNING id
                """, Long.class, documentId, index, "块 " + index, literal, model);
        return id == null ? 0L : id;
    }

    private static String sha(String status, boolean deleted) {
        // CHAR(64)：状态 + 删除标记 + 自增序号，保证每个文档唯一
        String seed = (status + (deleted ? "D" : "A") + SHA_SEQUENCE.incrementAndGet()).toLowerCase();
        return (seed + "0".repeat(64)).substring(0, 64);
    }

    /** 前两位有效、其余补 0 的 1024 维向量。 */
    private static String vector(float first, float second) {
        StringBuilder builder = new StringBuilder(DIMENSIONS * 3);
        builder.append('[').append(first).append(',').append(second);
        for (int i = 2; i < DIMENSIONS; i++) {
            builder.append(",0");
        }
        return builder.append(']').toString();
    }
}
