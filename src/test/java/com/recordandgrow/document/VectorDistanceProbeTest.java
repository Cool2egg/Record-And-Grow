package com.recordandgrow.document;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * pgvector 读取侧的最小探针：{@code <=>} + {@code cast(? AS vector)}。
 *
 * <p>模块五的 {@link VectorCastProbeTest} 只证明了写入侧；文档检索要在
 * {@code ORDER BY embedding <=> cast(? AS vector)} 上走 HNSW，这是模块六
 * 唯一没有先例的 JDBC 路径。单独成类，不依赖 V2 迁移，失败时先修它，
 * 不要继续写 {@code DocumentRetriever}。
 *
 * <p>走的是和 MyBatis 完全相同的路径：{@code PreparedStatement.setString}。
 * 需要 Docker；没有 Docker 的环境自动跳过，不算通过。
 */
@Testcontainers(disabledWithoutDocker = true)
class VectorDistanceProbeTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("recordandgrow")
            .withUsername("recordandgrow")
            .withPassword("recordandgrow");

    @Test
    void stringParameterShouldBeUsableInCosineDistanceOrderBy() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {

            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TEMP TABLE probe (id INT PRIMARY KEY, v vector(1024))");

            insert(connection, 1, unitVector(1024, 0));
            insert(connection, 2, unitVector(1024, 1));
            insert(connection, 3, blendVector(1024));

            String query = "SELECT id, v <=> cast(? AS vector) AS distance "
                    + "FROM probe ORDER BY v <=> cast(? AS vector), id";
            try (PreparedStatement select = connection.prepareStatement(query)) {
                select.setString(1, unitVector(1024, 0));
                select.setString(2, unitVector(1024, 0));

                try (ResultSet rs = select.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("id")).isEqualTo(1);
                    assertThat(rs.getDouble("distance")).isCloseTo(0.0, within(1e-9));

                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("id")).isEqualTo(3);
                    assertThat(rs.getDouble("distance")).isCloseTo(0.0298575, within(1e-5));

                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("id")).isEqualTo(2);
                    assertThat(rs.getDouble("distance")).isCloseTo(1.0, within(1e-9));

                    assertThat(rs.next()).isFalse();
                }
            }
        }
    }

    @Test
    void distanceShouldRejectWrongDimensions() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {

            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TEMP TABLE probe (id INT PRIMARY KEY, v vector(1024))");
            insert(connection, 1, unitVector(1024, 0));

            boolean rejected = false;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT v <=> cast(? AS vector) FROM probe")) {
                select.setString(1, unitVector(512, 0));
                select.executeQuery();
            } catch (java.sql.SQLException e) {
                rejected = true;
                assertThat(e.getMessage()).containsIgnoringCase("dimension");
            }

            assertThat(rejected).as("vector(1024) 列必须拒绝 512 维查询向量").isTrue();
        }
    }

    private static void insert(Connection connection, int id, String vector) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO probe (id, v) VALUES (?, cast(? AS vector))")) {
            insert.setInt(1, id);
            insert.setString(2, vector);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    /** 第 index 位为 1，其余为 0；用于构造余弦距离可精确预期的向量。 */
    private static String unitVector(int dimensions, int index) {
        StringBuilder builder = new StringBuilder(dimensions * 3);
        builder.append('[');
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(i == index ? "1" : "0");
        }
        return builder.append(']').toString();
    }

    /** [0.8, 0.2, 0, ...]：与 [1,0,...] 的余弦距离约 0.0298575，用来验证不是只按 id 排。 */
    private static String blendVector(int dimensions) {
        StringBuilder builder = new StringBuilder(dimensions * 3);
        builder.append('[');
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(i == 0 ? "0.8" : i == 1 ? "0.2" : "0");
        }
        return builder.append(']').toString();
    }
}
