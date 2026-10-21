package com.recordandgrow.document;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2 迁移的库级验证：扩展、向量维度、索引形态、部分唯一索引语义、级联删除。
 *
 * <p>用纯 JDBC 而不是 {@code @SpringBootTest}：这里验的是 DDL 本身，不需要 Spring 上下文；
 * 顺带把设计稿里唯一的 {@code [推断]}——「JDBC 传字符串 + cast(? as vector) 能否写入」——
 * 用和 MyBatis 完全相同的 setString 路径验掉。本测试依赖 Docker。
 */
@Testcontainers
class DocumentSchemaIntegrationTest {

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
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM document_chunk");
            statement.execute("DELETE FROM document");
        }
    }

    @Test
    void vectorExtensionShouldBeInstalled() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT extversion FROM pg_extension WHERE extname = 'vector'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("extversion")).isNotBlank();
        }
    }

    @Test
    void embeddingColumnShouldBeVector1024() throws Exception {
        long documentId = insertDocument("a".repeat(64));
        long chunkId = insertChunk(documentId, 0, "正文", null);

        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_chunk SET embedding = cast(? AS vector) WHERE id = ?")) {
            ps.setString(1, vectorLiteral(1024));
            ps.setLong(2, chunkId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT vector_dims(embedding) AS dims FROM document_chunk WHERE id = " + chunkId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("dims")).isEqualTo(1024);
        }
    }

    /**
     * JDBC 参数是 varchar，靠显式 cast 转 vector。这条不通，整个方案要换实现
     * （改驱动 scope + PGobject），所以必须在写业务前先跑绿。
     */
    @Test
    void castFromJdbcStringShouldInsertVector() throws Exception {
        long documentId = insertDocument("b".repeat(64));
        long chunkId = insertChunk(documentId, 0, "正文", null);

        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_chunk SET embedding = cast(? AS vector), embedding_model = ? WHERE id = ?")) {
            ps.setString(1, vectorLiteral(1024));
            ps.setString(2, "text-embedding-v4");
            ps.setLong(3, chunkId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT embedding_model FROM document_chunk WHERE id = " + chunkId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("embedding_model")).isEqualTo("text-embedding-v4");
        }
    }

    @Test
    void hnswIndexShouldExistOnEmbedding() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_document_chunk_embedding'")) {
            assertThat(rs.next()).isTrue();
            String definition = rs.getString("indexdef").toLowerCase(Locale.ROOT);
            assertThat(definition).contains("hnsw").contains("vector_cosine_ops");
        }
    }

    @Test
    void sha256UniqueIndexShouldOnlyCoverActiveRows() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_document_sha256_active'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("indexdef").toLowerCase(Locale.ROOT)).contains("deleted = false");
        }

        String sha = "c".repeat(64);
        long firstId = insertDocument(sha);

        // 活跃行重复 → 唯一索引冲突
        assertThatThrownBy(() -> insertDocument(sha))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));

        // 软删之后允许重新上传同一份内容
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE document SET deleted = TRUE WHERE id = " + firstId);
        }
        assertThat(insertDocument(sha)).isPositive();
    }

    @Test
    void deletingDocumentShouldCascadeToChunks() throws Exception {
        long documentId = insertDocument("d".repeat(64));
        insertChunk(documentId, 0, "第一块", null);
        insertChunk(documentId, 1, "第二块", "标题");

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM document WHERE id = " + documentId);
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM document_chunk WHERE document_id = " + documentId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    private long insertDocument(String sha256) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO document (title, file_name, mime_type, file_size, storage_path, content_sha256)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)) {
            ps.setString(1, "测试文档");
            ps.setString(2, "sample.txt");
            ps.setString(3, "text/plain");
            ps.setLong(4, 12L);
            ps.setString(5, "./data/documents/sample.txt");
            ps.setString(6, sha256);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("id");
            }
        }
    }

    private long insertChunk(long documentId, int index, String content, String heading) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO document_chunk
                    (document_id, chunk_index, content, heading, char_start, char_end, char_count, token_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)) {
            ps.setLong(1, documentId);
            ps.setInt(2, index);
            ps.setString(3, content);
            ps.setString(4, heading);
            ps.setInt(5, 0);
            ps.setInt(6, content.codePointCount(0, content.length()));
            ps.setInt(7, content.codePointCount(0, content.length()));
            ps.setInt(8, content.length());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("id");
            }
        }
    }

    /** 构造 "[0.1,0.1,...]" 形式的向量字面量，必须用 Locale.ROOT 以免小数点变逗号。 */
    private static String vectorLiteral(int dimensions) {
        StringBuilder builder = new StringBuilder(dimensions * 4);
        builder.append('[');
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(String.format(Locale.ROOT, "%.4f", 0.1f));
        }
        return builder.append(']').toString();
    }
}
