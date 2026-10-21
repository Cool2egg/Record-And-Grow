package com.recordandgrow.document;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块六第 0 步的真表探针：在 V2 迁移后的真实表上跑设计稿 6.1 的 SQL。
 *
 * <p>与 {@link VectorDistanceProbeTest} 的区别：那边是临时表 + 裸 {@code <=>}，
 * 这边是 {@code document} / {@code document_chunk} 真表 + join 过滤 + 投影 + {@code LIMIT}，
 * 也就是 {@code DocumentChunkMapper.search} 将要执行的完整语句。
 *
 * <p>断言只覆盖「正确性」：过滤、排序、条数。执行计划只打印不断言——小表走顺序扫描是合法的，
 * 计划要抄进 {@code docs/verification/06-document-retrieval.md} 做记录。
 *
 * <p>需要 Docker；没有 Docker 的环境自动跳过，不算通过。
 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentSearchProbeTest {

    private static final int DIMENSIONS = 1024;
    private static final String MODEL = "text-embedding-v4";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("recordandgrow")
            .withUsername("recordandgrow")
            .withPassword("recordandgrow");

    private static Connection connection;

    @BeforeAll
    static void migrateAndConnect() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        seed();
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void searchSqlShouldFilterSortAndLimitOnRealTables() throws Exception {
        String query = vector(1.0f, 0.0f);

        List<Long> ids = search(query, 24);
        assertThat(ids).as("20 行全部命中").hasSize(20);

        // 种子向量按 i 递增逐渐偏离 [1,0,...]，所以 1..20 顺序就是相似度降序
        List<Long> expected = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            expected.add((long) i);
        }
        assertThat(ids).as("相似度降序；平局才按 document_id/chunk_index/id").containsExactlyElementsOf(expected);

        assertThat(search(query, 5)).as("LIMIT 在 SQL 侧生效").hasSize(5);
    }

    @Test
    void searchSqlShouldJoinFilterReadyActiveAndModel() throws Exception {
        insertDocument(100, "DELETED", MODEL, true);
        insertDocument(101, "PENDING", MODEL, false);
        insertDocument(102, "READY", "other-model", false);
        insertChunk(100, 6100, vector(0.99f, 0.02f), MODEL);
        insertChunk(101, 6101, vector(0.99f, 0.02f), MODEL);
        insertChunk(102, 6102, vector(0.99f, 0.02f), MODEL);
        insertChunk(103, 6103, null, MODEL);
        insertChunk(103, 6104, vector(0.99f, 0.02f), "other-model");

        List<Long> ids = search(vector(1.0f, 0.0f), 24);

        assertThat(ids).doesNotContain(100L, 101L, 102L, 103L);
        assertThat(ids).hasSize(20);
    }

    @Test
    void explainShouldBeRecorded() throws Exception {
        String sql = """
                EXPLAIN (ANALYZE, BUFFERS, COSTS)
                SELECT c.id AS chunk_id, c.document_id, d.title, c.chunk_index, c.heading, c.content,
                       c.char_start, c.char_end, c.token_count,
                       c.embedding <=> cast(? AS vector) AS distance
                  FROM document_chunk c
                  JOIN document d ON d.id = c.document_id
                 WHERE d.deleted = FALSE
                   AND d.status = 'READY'
                   AND c.embedding IS NOT NULL
                   AND c.embedding_model = ?
                 ORDER BY c.embedding <=> cast(? AS vector), c.document_id, c.chunk_index, c.id
                 LIMIT ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, vector(1.0f, 0.0f));
            ps.setString(2, MODEL);
            ps.setString(3, vector(1.0f, 0.0f));
            ps.setInt(4, 24);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> plan = new ArrayList<>();
                while (rs.next()) {
                    plan.add(rs.getString(1));
                }
                assertThat(plan).isNotEmpty();
                plan.forEach(System.out::println);
            }
        }
    }

    // ---- 与设计稿 6.1 完全一致的查询 ----

    private static List<Long> search(String literal, int limit) throws Exception {
        String sql = """
                SELECT c.id AS chunk_id, c.document_id, d.title, c.chunk_index, c.heading, c.content,
                       c.char_start, c.char_end, c.token_count,
                       c.embedding <=> cast(? AS vector) AS distance
                  FROM document_chunk c
                  JOIN document d ON d.id = c.document_id
                 WHERE d.deleted = FALSE
                   AND d.status = 'READY'
                   AND c.embedding IS NOT NULL
                   AND c.embedding_model = ?
                 ORDER BY c.embedding <=> cast(? AS vector), c.document_id, c.chunk_index, c.id
                 LIMIT ?
                """;
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, literal);
            ps.setString(2, MODEL);
            ps.setString(3, literal);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    assertThat(rs.getDouble("distance")).isBetween(-1.0, 2.0);
                    ids.add(rs.getLong("chunk_id"));
                }
            }
        }
        return ids;
    }

    // ---- 种子数据：1 个 READY 文档 + 20 个块 ----

    private static void seed() throws Exception {
        long documentId = insertDocument("READY", true);
        for (int i = 1; i <= 20; i++) {
            float b = i * 0.04f;
            insertChunk(documentId, i, vector(1.0f - b, b), MODEL);
        }
    }

    private static long insertDocument(String status, boolean deleted) throws Exception {
        return insertDocument(0, status, MODEL, deleted);
    }

    /** 用显式 id 建文档，方便在断言里直接引用。 */
    private static long insertDocument(int id, String status, String model, boolean deleted) throws Exception {
        String sql = id == 0
                ? """
                  INSERT INTO document (title, file_name, mime_type, file_size, storage_path, content_sha256,
                                        status, deleted)
                  VALUES (?, 'p.txt', 'text/plain', 1, './data/p.txt', ?, ?, ?)
                  RETURNING id
                  """
                : """
                  INSERT INTO document (id, title, file_name, mime_type, file_size, storage_path, content_sha256,
                                        status, deleted)
                  VALUES (?, ?, 'p.txt', 'text/plain', 1, './data/p.txt', ?, ?, ?)
                  RETURNING id
                  """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            if (id != 0) {
                ps.setInt(index++, id);
            }
            ps.setString(index++, "探针文档 " + (id == 0 ? "main" : id));
            ps.setString(index++, sha256(id));
            ps.setString(index++, status);
            ps.setBoolean(index, deleted);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("id");
            }
        }
    }

    private static long insertChunk(long documentId, int chunkIndex, String literal, String model) throws Exception {
        String sql = """
                INSERT INTO document_chunk
                    (document_id, chunk_index, content, heading, char_start, char_end, char_count, token_count,
                     embedding, embedding_model)
                VALUES (?, ?, ?, ?, 0, 10, 10, 10, cast(? AS vector), ?)
                RETURNING id
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, documentId);
            ps.setInt(2, chunkIndex);
            ps.setString(3, "块 " + chunkIndex);
            ps.setString(4, "标题");
            if (literal == null) {
                ps.setObject(5, null);
            } else {
                ps.setString(5, literal);
            }
            ps.setString(6, model);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("id");
            }
        }
    }

    /** 前两位有效、其余补 0 的 1024 维向量。 */
    private static String vector(float first, float second) {
        StringBuilder builder = new StringBuilder(DIMENSIONS * 3);
        builder.append('[');
        for (int i = 0; i < DIMENSIONS; i++) {
            if (i > 0) {
                builder.append(',');
            }
            if (i == 0) {
                builder.append(Float.toString(first));
            } else if (i == 1) {
                builder.append(Float.toString(second));
            } else {
                builder.append('0');
            }
        }
        return builder.append(']').toString();
    }

    /** 固定 64 位十六进制，满足 content_sha256 CHAR(64)。 */
    private static String sha256(int id) {
        String hex = Integer.toHexString(id).repeat(64);
        return hex.substring(0, 64);
    }
}
